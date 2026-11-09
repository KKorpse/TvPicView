package com.kkorpse.tvpicview;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 每个播放目录维护收藏/删除标记。
 * 优先写入目录内的 tvpicview_state.json；如果电视的 MediaStore 不允许
 * 在该目录创建文件，则自动降级到应用私有目录，保证标记功能仍然可用。
 */
public final class ActionStateStore {

    public static final String FAVORITE = "FAVORITE";
    public static final String DELETED = "DELETED";
    public static final String FILE_NAME = "tvpicview_state.json";

    private static final String TAG = "TvPicView";
    private static final String MIME_JSON = "application/json";

    private ActionStateStore() {
    }

    public static final class Target {
        public final Uri uri;
        public final File file;
        public final boolean inDirectory;

        Target(Uri uri, File file, boolean inDirectory) {
            this.uri = uri;
            this.file = file;
            this.inDirectory = inDirectory;
        }

        public String describe() {
            if (uri != null) {
                return uri.toString();
            }
            return file == null ? "null" : file.getAbsolutePath();
        }
    }

    public static Target findOrCreate(Context context, String directoryKey,
                                      String treeUri, String volumeName,
                                      String relativePath, String directoryPath) throws Exception {
        Target direct = tryDirectDirectory(directoryPath);
        if (direct != null) {
            return direct;
        }

        try {
            Uri uri;
            if (treeUri != null && treeUri.length() > 0) {
                uri = findOrCreateSaf(context, Uri.parse(treeUri));
            } else if (volumeName != null && volumeName.length() > 0) {
                uri = findOrCreateMediaStore(context, volumeName, relativePath);
            } else {
                throw new IllegalStateException("没有可用的目录上下文");
            }
            return new Target(uri, null, true);
        } catch (Throwable e) {
            Log.w(TAG, "[StateFile] 目录内状态文件不可用，降级到应用私有目录", e);
            return createPrivateTarget(context, directoryKey);
        }
    }

    private static Target tryDirectDirectory(String directoryPath) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || !Environment.isExternalStorageManager()
                || directoryPath == null || directoryPath.length() == 0) {
            return null;
        }
        File dir = new File(directoryPath);
        if (!dir.isDirectory()) {
            return null;
        }
        File file = new File(dir, FILE_NAME);
        try {
            if (!file.exists()) {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write("{\"version\":1,\"items\":{}}"
                            .getBytes(StandardCharsets.UTF_8));
                }
            }
            Log.i(TAG, "[StateFile] 使用目录状态文件（所有文件访问）: " + file);
            return new Target(null, file, true);
        } catch (Exception e) {
            Log.w(TAG, "[StateFile] 目录直接写入失败: " + file, e);
            return null;
        }
    }

    private static Target createPrivateTarget(Context context, String directoryKey) throws Exception {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "state");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建私有状态目录: " + dir);
        }
        String hash = Integer.toHexString(
                (directoryKey == null ? "" : directoryKey).hashCode());
        File file = new File(dir, "tvpicview_state_" + hash + ".json");
        if (!file.exists()) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("{\"version\":1,\"items\":{}}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
        Log.i(TAG, "[StateFile] 使用私有状态文件: " + file);
        return new Target(null, file, false);
    }

    private static Uri findOrCreateSaf(Context context, Uri treeUri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId);

        Cursor cursor = null;
        try {
            cursor = resolver.query(childrenUri, new String[]{
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE
            }, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (name != null && name.startsWith(FILE_NAME)) {
                        return DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, cursor.getString(0));
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Uri created = DocumentsContract.createDocument(
                resolver, rootDocument, MIME_JSON, FILE_NAME);
        if (created == null) {
            throw new IllegalStateException("系统文件选择器无法创建 " + FILE_NAME);
        }
        Log.i(TAG, "[StateFile] 已创建 SAF 状态文件: " + created);
        return created;
    }

    private static Uri findOrCreateMediaStore(Context context, String volumeName,
                                              String relativePath) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new UnsupportedOperationException(
                    "媒体库写入需要 Android 10 及以上");
        }
        ContentResolver resolver = context.getContentResolver();
        String relative = relativePath == null ? "" : relativePath;
        if (relative.length() > 0 && !relative.endsWith("/")) {
            relative += "/";
        }

        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = new String[]{relative, FILE_NAME + "%"};

        Uri filesUri = MediaStore.Files.getContentUri(volumeName);
        Uri imagesUri = MediaStore.Images.Media.getContentUri(volumeName);
        Uri videosUri = MediaStore.Video.Media.getContentUri(volumeName);

        Uri existing = queryStateFile(resolver, filesUri, selection, args);
        if (existing == null) {
            existing = queryStateFile(resolver, imagesUri, selection, args);
        }
        if (existing == null) {
            existing = queryStateFile(resolver, videosUri, selection, args);
        }
        if (existing != null) {
            return existing;
        }

        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        Uri firstCollection;
        String firstMime;
        if (!"external_primary".equals(volumeName)) {
            firstCollection = filesUri;
            firstMime = MIME_JSON;
        } else if (lower.startsWith("movies/")) {
            firstCollection = videosUri;
            firstMime = "video/mp4";
        } else if (lower.startsWith("pictures/") || lower.startsWith("dcim/")) {
            firstCollection = imagesUri;
            firstMime = "image/jpeg";
        } else if (lower.startsWith("download/") || lower.startsWith("documents/")) {
            firstCollection = filesUri;
            firstMime = MIME_JSON;
        } else {
            firstCollection = filesUri;
            firstMime = MIME_JSON;
        }

        Uri[] collections = new Uri[]{firstCollection, filesUri, videosUri, imagesUri};
        String[] mimes = new String[]{firstMime, MIME_JSON, "video/mp4", "image/jpeg"};
        Exception lastError = null;
        for (int i = 0; i < collections.length; i++) {
            try {
                Uri created = insertStateFile(resolver, collections[i], relative, mimes[i]);
                Log.i(TAG, "[StateFile] 已创建 MediaStore 状态文件: " + created
                        + " relative=" + relative + " mime=" + mimes[i]);
                return created;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "[StateFile] 写入集合失败: " + collections[i], e);
            }
        }
        throw new IllegalStateException("MediaStore 无法创建状态文件", lastError);
    }

    private static Uri queryStateFile(ContentResolver resolver, Uri collection,
                                      String selection, String[] args) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(collection, new String[]{"_id"},
                    selection, args, "_id DESC");
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        } catch (Exception e) {
            Log.w(TAG, "[StateFile] 查询状态文件失败: " + collection, e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static Uri insertStateFile(ContentResolver resolver, Uri collection,
                                       String relativePath, String mimeType) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri created = resolver.insert(collection, values);
        if (created == null) {
            throw new IllegalStateException("MediaStore 无法创建 " + FILE_NAME);
        }

        try (OutputStream output = resolver.openOutputStream(created)) {
            if (output != null) {
                output.write("{\"version\":1,\"items\":{}}"
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "[StateFile] 初始化状态文件内容失败", e);
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(created, done, null, null);
        return created;
    }

    public static Map<String, String> read(Context context, Target target) throws Exception {
        Map<String, String> result = new HashMap<>();
        if (target == null) {
            return result;
        }

        String json;
        if (target.uri != null) {
            try (InputStream input = context.getContentResolver().openInputStream(target.uri)) {
                json = readStream(input);
            }
        } else if (target.file != null) {
            try (InputStream input = new FileInputStream(target.file)) {
                json = readStream(input);
            }
        } else {
            return result;
        }

        if (json == null || json.trim().length() == 0) {
            return result;
        }
        JSONObject root = new JSONObject(json);
        JSONObject items = root.optJSONObject("items");
        if (items == null) {
            return result;
        }
        Iterator<String> keys = items.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            String state = items.optString(name, "");
            if (FAVORITE.equals(state) || DELETED.equals(state)) {
                result.put(name, state);
            }
        }
        return result;
    }

    private static String readStream(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    public static void write(Context context, Target target, String directoryKey,
                             Map<String, String> states) throws Exception {
        if (target == null) {
            throw new IllegalStateException("状态文件 URI 为空");
        }
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("updatedAt", System.currentTimeMillis());
        root.put("directory", directoryKey == null ? "" : directoryKey);
        JSONObject items = new JSONObject();
        for (Map.Entry<String, String> entry : states.entrySet()) {
            items.put(entry.getKey(), entry.getValue());
        }
        root.put("items", items);

        byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
        if (target.uri != null) {
            ContentResolver resolver = context.getContentResolver();
            try (OutputStream output = resolver.openOutputStream(target.uri, "wt")) {
                if (output == null) {
                    throw new IllegalStateException("无法打开状态文件写入");
                }
                output.write(bytes);
            }
        } else if (target.file != null) {
            try (FileOutputStream output = new FileOutputStream(target.file)) {
                output.write(bytes);
            }
        } else {
            throw new IllegalStateException("状态文件目标为空");
        }
        Log.i(TAG, "[StateFile] 已保存 " + target.describe()
                + " entries=" + states.size());
    }
}
