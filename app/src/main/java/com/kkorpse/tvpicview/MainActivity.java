package com.kkorpse.tvpicview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final String TAG = "TvPicView";
    private static final int REQ_PICK_DIR = 1001;
    private static final int REQ_MEDIA_PERM = 1002;
    private static final int MAX_FILES = 2000;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_LOG_LINES = 800;
    private static final int MAX_ERRORS = 50;

    private static final String COL_BUCKET_ID = "bucket_id";
    private static final String COL_BUCKET_NAME = "bucket_display_name";

    private TextView pathView;
    private TextView statusView;
    private TextView logView;
    private ListView resultList;
    private Button pickButton;
    private Button mediaButton;
    private Button playButton;

    private Uri treeUri;
    private boolean usingMediaBucket;
    private long mediaBucketId = -1L;
    private String mediaBucketName;

    private final List<String> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private final Deque<String> diagnostics = new ArrayDeque<>();
    private final AtomicBoolean logUpdateScheduled = new AtomicBoolean(false);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        pathView = findViewById(R.id.pathView);
        statusView = findViewById(R.id.statusView);
        logView = findViewById(R.id.logView);
        resultList = findViewById(R.id.resultList);
        pickButton = findViewById(R.id.pickButton);
        mediaButton = findViewById(R.id.mediaButton);
        playButton = findViewById(R.id.playButton);

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setTextSize(18f);
                view.setGravity(Gravity.CENTER_VERTICAL);
                view.setPadding(dp(14), dp(8), dp(14), dp(8));
                view.setMinHeight(dp(48));
                return view;
            }
        };
        resultList.setAdapter(adapter);

        pickButton.setOnClickListener(v -> pickDirectory());
        mediaButton.setOnClickListener(v -> chooseMediaLibrary());
        playButton.setOnClickListener(v -> play());
        logView.setOnClickListener(v -> showLogDialog());
        playButton.setEnabled(false);

        logEnvironment();
        updateLogView();
        pickButton.post(pickButton::requestFocus);
    }

    // ------------------------------------------------------------
    // 启动环境与诊断
    // ------------------------------------------------------------

    private void logEnvironment() {
        String version = "unknown";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            version = info.versionName + " (" + code + ")";
        } catch (Exception e) {
            logError("App", "读取应用版本失败", e);
        }

        log("App", "启动 " + getPackageName() + " v" + version);
        log("Device", Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + " | " + Build.DISPLAY);
        log("Device", "屏幕=" + getResources().getDisplayMetrics().widthPixels + "x"
                + getResources().getDisplayMetrics().heightPixels
                + " density=" + getResources().getDisplayMetrics().density);
        PackageManager pm = getPackageManager();
        log("Device", "leanback=" + pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                + ", television=" + pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                + ", touchscreen=" + pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN));
        log("Storage", "externalState=" + Environment.getExternalStorageState());
        log("Permission", mediaPermissionSummary());
        log("SAF", "已持久化目录权限数量="
                + getContentResolver().getPersistedUriPermissions().size());

        Intent intent = buildTreePickerIntent();
        ResolveInfo info = pm.resolveActivity(intent, 0);
        if (info == null || info.activityInfo == null) {
            log("Picker", "系统文件选择器：未找到");
        } else {
            log("Picker", "系统文件选择器=" + info.activityInfo.packageName + "/"
                    + info.activityInfo.name + " stub=" + isStubFilePicker(info.activityInfo));
        }
    }

    private Intent buildTreePickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    private void log(String stage, String message) {
        String time;
        synchronized (timeFormat) {
            time = timeFormat.format(new Date());
        }
        String line = time + " [" + stage + "] " + oneLine(message);
        Log.i(TAG, line);
        appendDiagnostic(line);
    }

    private void logError(String stage, String message, Throwable error) {
        String time;
        synchronized (timeFormat) {
            time = timeFormat.format(new Date());
        }
        String line = time + " [" + stage + "] " + oneLine(message)
                + (error == null ? "" : " | " + errorSummary(error));
        Log.e(TAG, line, error);
        appendDiagnostic(line);
    }

    private void appendDiagnostic(String line) {
        synchronized (diagnostics) {
            diagnostics.addLast(line);
            while (diagnostics.size() > MAX_LOG_LINES) {
                diagnostics.removeFirst();
            }
        }
        scheduleLogViewUpdate();
    }

    private void scheduleLogViewUpdate() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateLogView();
            return;
        }
        if (!logUpdateScheduled.compareAndSet(false, true)) {
            return;
        }
        runOnUiThread(() -> {
            logUpdateScheduled.set(false);
            updateLogView();
        });
    }

    private void updateLogView() {
        if (logView == null || isFinishing() || isDestroyed()) {
            return;
        }
        StringBuilder builder = new StringBuilder(getString(R.string.diagnostics_title));
        synchronized (diagnostics) {
            int skip = Math.max(0, diagnostics.size() - 4);
            int index = 0;
            for (String line : diagnostics) {
                if (index++ >= skip) {
                    builder.append('\n').append(truncate(line, 105));
                }
            }
        }
        logView.setText(builder.toString());
    }

    private void showLogDialog() {
        log("App", "打开诊断日志");
        List<String> lines;
        synchronized (diagnostics) {
            lines = new ArrayList<>(diagnostics);
        }
        if (lines.isEmpty()) {
            lines.add(getString(R.string.log_empty));
        }

        ArrayAdapter<String> logAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, lines) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(MainActivity.this.getColor(R.color.text_primary));
                view.setTextSize(12f);
                view.setTypeface(Typeface.MONOSPACE);
                view.setPadding(dp(10), dp(6), dp(10), dp(6));
                return view;
            }
        };

        ListView list = new ListView(this);
        list.setAdapter(logAdapter);
        list.setBackgroundColor(getColor(R.color.panel));
        list.setDivider(null);
        list.setFocusable(true);

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int height = Math.max(dp(200), Math.min(dp(520), screenHeight - dp(240)));
        FrameLayout container = new FrameLayout(this);
        container.setPadding(dp(16), dp(8), dp(16), dp(8));
        container.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.log_dialog_title)
                .setView(container)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.clear, (d, which) -> clearDiagnostics())
                .create();
        dialog.setOnShowListener(d -> {
            list.setSelection(Math.max(0, logAdapter.getCount() - 1));
            list.post(list::requestFocus);
        });
        dialog.show();
    }

    private void clearDiagnostics() {
        synchronized (diagnostics) {
            diagnostics.clear();
        }
        updateLogView();
        log("App", "诊断日志已清空");
    }

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(getColor(error ? R.color.error : R.color.text_secondary));
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private String errorSummary(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        StackTraceElement[] trace = error.getStackTrace();
        String where = "";
        if (trace.length > 0) {
            StackTraceElement first = trace[0];
            where = " @ " + first.getClassName() + "." + first.getMethodName()
                    + ":" + first.getLineNumber();
        }
        return error.getClass().getSimpleName()
                + (message == null ? "" : ": " + oneLine(message)) + where;
    }

    // ------------------------------------------------------------
    // 目录选择：系统选择器 / 媒体库兜底
    // ------------------------------------------------------------

    private void pickDirectory() {
        Intent intent = buildTreePickerIntent();
        ResolveInfo resolveInfo = getPackageManager().resolveActivity(intent, 0);

        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            log("Picker", "没有系统文件选择器");
            Toast.makeText(this, R.string.picker_missing_fallback, Toast.LENGTH_LONG).show();
            showMediaLibraryFallback("系统选择器缺失");
            return;
        }

        String component = resolveInfo.activityInfo.packageName + "/"
                + resolveInfo.activityInfo.name;
        boolean stub = isStubFilePicker(resolveInfo.activityInfo);
        log("Picker", "resolve=" + component + " stub=" + stub);

        if (stub) {
            Toast.makeText(this, R.string.picker_stub_fallback, Toast.LENGTH_LONG).show();
            showMediaLibraryFallback("系统选择器是 Stub: " + component);
            return;
        }

        try {
            log("Picker", "启动系统文件选择器");
            startActivityForResult(intent, REQ_PICK_DIR);
        } catch (ActivityNotFoundException e) {
            logError("Picker", "系统文件选择器不存在", e);
            Toast.makeText(this, R.string.picker_missing_fallback, Toast.LENGTH_LONG).show();
            showMediaLibraryFallback("ActivityNotFoundException");
        } catch (Exception e) {
            logError("Picker", "启动系统文件选择器失败", e);
            setStatus(getString(R.string.picker_missing_fallback), true);
            showMediaLibraryFallback("启动异常: " + errorSummary(e));
        }
    }

    /**
     * 部分 Android TV / Google TV 固件把 DocumentsUI 换成空壳 Activity：
     * 不崩溃，也不显示选择器。识别后直接走 MediaStore。
     */
    private boolean isStubFilePicker(ActivityInfo info) {
        String packageName = info.packageName == null
                ? "" : info.packageName.toLowerCase(Locale.ROOT);
        String className = info.name == null
                ? "" : info.name.toLowerCase(Locale.ROOT);
        return packageName.contains("frameworkpackagestubs")
                || packageName.contains(".stub")
                || className.contains("stub");
    }

    private void chooseMediaLibrary() {
        log("MediaStore", "用户点击“媒体库目录”");
        showMediaLibraryFallback("用户主动选择");
    }

    private void showMediaLibraryFallback(String reason) {
        log("MediaStore", "回退原因: " + reason);
        if (!hasMediaPermission()) {
            log("Permission", "媒体权限不足: " + mediaPermissionSummary());
            requestMediaPermission();
            return;
        }
        showMediaBucketDialog();
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaPermission() {
        log("Permission", "请求媒体权限: " + mediaPermissionSummary());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
            }, REQ_MEDIA_PERM);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQ_MEDIA_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        log("Permission", "onRequestPermissionsResult request=" + requestCode);
        for (int i = 0; i < permissions.length; i++) {
            boolean granted = i < grantResults.length
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED;
            log("Permission", permissions[i] + " -> " + (granted ? "GRANTED" : "DENIED"));
        }

        if (requestCode != REQ_MEDIA_PERM) {
            return;
        }
        if (hasMediaPermission()) {
            log("Permission", "媒体权限已获得");
            showMediaBucketDialog();
        } else {
            String summary = mediaPermissionSummary();
            log("Permission", "媒体权限被拒绝: " + summary);
            setStatus(getString(R.string.permission_denied_detail, summary), true);
            showPermissionSettingsDialog(summary);
        }
    }

    private void showPermissionSettingsDialog(String summary) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_dialog_title)
                .setMessage(getString(R.string.permission_dialog_message, summary))
                .setPositiveButton(R.string.open_settings, (d, which) -> {
                    log("Permission", "打开应用设置");
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        logError("Permission", "打开应用设置失败", e);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String mediaPermissionSummary() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            StringBuilder summary = new StringBuilder();
            summary.append("images=")
                    .append(permissionLabel(Manifest.permission.READ_MEDIA_IMAGES))
                    .append(", videos=")
                    .append(permissionLabel(Manifest.permission.READ_MEDIA_VIDEO));
            if (Build.VERSION.SDK_INT >= 34) {
                summary.append(", selected=")
                        .append(permissionLabel(
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED));
            }
            return summary.toString();
        }
        return "storage=" + permissionLabel(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private String permissionLabel(String permission) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "n/a";
        }
        if (permission.startsWith("android.permission.READ_MEDIA_")
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "n/a";
        }
        if ("android.permission.READ_MEDIA_VISUAL_USER_SELECTED".equals(permission)
                && Build.VERSION.SDK_INT < 34) {
            return "n/a";
        }
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                ? "granted" : "denied";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        log("Picker", "onActivityResult request=" + requestCode
                + " result=" + resultCode
                + " data=" + (data == null ? "null" : String.valueOf(data.getData()))
                + " flags=0x" + (data == null ? "0" : Integer.toHexString(data.getFlags())));

        if (requestCode != REQ_PICK_DIR) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("Picker", "用户取消系统选择器");
            setStatus(getString(R.string.pick_cancelled), false);
            return;
        }

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            log("Picker", "已持久化目录权限: " + uri);
        } catch (SecurityException e) {
            logError("Picker", "无法持久化目录权限（本次仍可读取）: " + uri, e);
        }

        treeUri = uri;
        usingMediaBucket = false;
        mediaBucketId = -1L;
        mediaBucketName = null;

        rows.clear();
        adapter.notifyDataSetChanged();
        String path = describeTree(uri);
        pathView.setText(getString(R.string.selected_path, path));
        setStatus(getString(R.string.ready_to_scan), false);
        playButton.setEnabled(true);
        playButton.requestFocus();
        log("Picker", "已选择系统目录: " + path + " uri=" + uri);
    }

    // ------------------------------------------------------------
    // 播放 / 扫描
    // ------------------------------------------------------------

    private void play() {
        log("Play", "点击播放 treeUri=" + treeUri
                + " usingMediaBucket=" + usingMediaBucket
                + " bucketId=" + mediaBucketId
                + " bucketName=" + mediaBucketName);
        if (treeUri != null) {
            startTreeScan();
        } else if (usingMediaBucket) {
            startMediaBucketScan();
        } else {
            log("Play", "尚未选择目录");
            setStatus(getString(R.string.no_directory), true);
            pickButton.requestFocus();
        }
    }

    private void startTreeScan() {
        final Uri uri = treeUri;
        final String path = describeTree(uri);
        setScanning(true);
        log("Scan", "开始扫描系统目录: " + path + " uri=" + uri);
        new Thread(() -> {
            List<String> found = new ArrayList<>();
            int[] counts = new int[]{0, 0};
            List<String> errors = new ArrayList<>();
            try {
                String rootId = DocumentsContract.getTreeDocumentId(uri);
                log("Scan", "treeDocumentId=" + rootId);
                scanTreeChildren(uri, rootId, "", 0, found, counts, errors);
            } catch (Exception e) {
                addError(errors, errorSummary(e));
                logError("Scan", "扫描根目录失败", e);
            }
            Collections.sort(found, String.CASE_INSENSITIVE_ORDER);
            logResults(path, found, counts, errors);
            final int errorCount = errors.size();
            final String firstError = errors.isEmpty() ? null : errors.get(0);
            runOnUiThread(() -> showResults(found, counts, firstError, errorCount));
        }, "tree-scan").start();
    }

    private void scanTreeChildren(Uri treeUri, String parentDocumentId, String prefix,
                                  int depth, List<String> out, int[] counts,
                                  List<String> errors) {
        if (out.size() >= MAX_FILES) {
            log("Scan", "达到文件上限 " + MAX_FILES + "，停止扫描");
            return;
        }
        if (depth > MAX_DEPTH) {
            log("Scan", "超过最大目录深度 " + MAX_DEPTH + "，跳过: " + prefix);
            return;
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, parentDocumentId);
        String[] projection = new String[]{
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE
        };
        log("Scan", "进入目录: " + (prefix.length() == 0 ? "(根目录)" : prefix)
                + " depth=" + depth);

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(childrenUri, projection, null, null, null);
            if (cursor == null) {
                throw new IllegalStateException("ContentResolver.query 返回 null");
            }
            log("Scan", "目录子项数量=" + cursor.getCount()
                    + " uri=" + childrenUri);

            while (cursor.moveToNext() && out.size() < MAX_FILES) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (name == null) {
                    name = "(未命名)";
                }

                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    log("Scan", "发现子目录: " + prefix + name);
                    scanTreeChildren(treeUri, documentId, prefix + name + "/",
                            depth + 1, out, counts, errors);
                } else if (isVideo(mime, name)) {
                    out.add("[视频] " + prefix + name);
                    counts[0]++;
                    log("Scan", "视频: " + prefix + name + " mime=" + mime);
                } else if (isImage(mime, name)) {
                    out.add("[图片] " + prefix + name);
                    counts[1]++;
                    log("Scan", "图片: " + prefix + name + " mime=" + mime);
                } else {
                    log("Scan", "跳过非媒体文件: " + prefix + name + " mime=" + mime);
                }
            }
        } catch (Exception e) {
            addError(errors, prefix + " -> " + errorSummary(e));
            logError("Scan", "读取目录失败: " + (prefix.length() == 0 ? "(根目录)" : prefix), e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void startMediaBucketScan() {
        final long bucketId = mediaBucketId;
        final String bucketName = mediaBucketName;
        setScanning(true);
        log("MediaStore", "开始扫描媒体库目录: " + bucketName + " bucketId=" + bucketId);
        new Thread(() -> {
            List<String> found = new ArrayList<>();
            int[] counts = new int[]{0, 0};
            List<String> errors = new ArrayList<>();

            try {
                collectBucketMedia(videoCollection(), true, bucketId, found, counts);
            } catch (Exception e) {
                addError(errors, "视频 -> " + errorSummary(e));
                logError("MediaStore", "读取视频媒体库失败", e);
            }
            try {
                collectBucketMedia(imageCollection(), false, bucketId, found, counts);
            } catch (Exception e) {
                addError(errors, "图片 -> " + errorSummary(e));
                logError("MediaStore", "读取图片媒体库失败", e);
            }

            Collections.sort(found, String.CASE_INSENSITIVE_ORDER);
            logResults(bucketName == null ? "媒体库" : bucketName, found, counts, errors);
            final int errorCount = errors.size();
            final String firstError = errors.isEmpty() ? null : errors.get(0);
            runOnUiThread(() -> showResults(found, counts, firstError, errorCount));
        }, "media-scan").start();
    }

    private void collectBucketMedia(Uri collection, boolean video, long bucketId,
                                    List<String> out, int[] counts) {
        String[] projection = new String[]{MediaStore.MediaColumns.DISPLAY_NAME};
        String selection = COL_BUCKET_ID + "=?";
        String[] args = new String[]{String.valueOf(bucketId)};
        log("MediaStore", "查询 " + (video ? "视频" : "图片") + " collection=" + collection
                + " bucketId=" + bucketId);

        Cursor cursor = null;
        int count = 0;
        try {
            cursor = getContentResolver().query(collection, projection, selection, args, null);
            if (cursor == null) {
                throw new IllegalStateException("ContentResolver.query 返回 null");
            }
            log("MediaStore", "cursor rows=" + cursor.getCount());
            while (cursor.moveToNext() && out.size() < MAX_FILES) {
                String name = cursor.getString(0);
                if (name != null) {
                    out.add((video ? "[视频] " : "[图片] ") + name);
                    counts[video ? 0 : 1]++;
                    count++;
                    log("MediaStore", "文件: " + (video ? "[视频] " : "[图片] ") + name);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            log("MediaStore", "bucketId=" + bucketId + " 返回 "
                    + (video ? "视频 " : "图片 ") + count + " 个");
        }
    }

    private void showResults(List<String> found, int[] counts, String error, int errorCount) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            setScanning(false);
            rows.clear();

            if (error != null && found.isEmpty()) {
                adapter.notifyDataSetChanged();
                log("Result", "扫描失败: " + error);
                setStatus(getString(R.string.scan_error_detail, error), true);
                pickButton.requestFocus();
                return;
            }

            rows.addAll(found);
            adapter.notifyDataSetChanged();

            if (found.isEmpty()) {
                log("Result", "扫描完成: 0 个文件");
                setStatus(getString(R.string.scan_empty), false);
                pickButton.requestFocus();
            } else {
                log("Result", "扫描完成: " + found.size() + " 个文件"
                        + "，视频=" + counts[0] + "，图片=" + counts[1]
                        + "，错误=" + errorCount);
                if (errorCount > 0) {
                    setStatus(getString(R.string.scan_result_warning,
                            found.size(), counts[0], counts[1], errorCount), true);
                } else {
                    setStatus(getString(R.string.scan_result,
                            found.size(), counts[0], counts[1]), false);
                }
                resultList.setSelection(0);
                resultList.requestFocus();
            }
        });
    }

    private void logResults(String directory, List<String> found,
                            int[] counts, List<String> errors) {
        log("Result", "目录: " + directory);
        log("Result", "文件总数=" + found.size()
                + "，视频=" + counts[0] + "，图片=" + counts[1]
                + "，目录错误=" + errors.size());
        for (String row : found) {
            Log.i(TAG, "[Result] " + row);
        }
        for (String error : errors) {
            log("Result", "错误: " + error);
        }
    }

    // ------------------------------------------------------------
    // MediaStore 目录列表
    // ------------------------------------------------------------

    private void showMediaBucketDialog() {
        mediaButton.setEnabled(false);
        setStatus(getString(R.string.reading_media), false);
        log("MediaStore", "开始查询媒体库目录");

        new Thread(() -> {
            Map<Long, String> buckets = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            try {
                collectBuckets(imageCollection(), buckets);
            } catch (Exception e) {
                addError(errors, "图片 -> " + errorSummary(e));
                logError("MediaStore", "读取图片目录失败", e);
            }
            try {
                collectBuckets(videoCollection(), buckets);
            } catch (Exception e) {
                addError(errors, "视频 -> " + errorSummary(e));
                logError("MediaStore", "读取视频目录失败", e);
            }

            List<Map.Entry<Long, String>> entries = new ArrayList<>(buckets.entrySet());
            Collections.sort(entries, (a, b) ->
                    a.getValue().compareToIgnoreCase(b.getValue()));
            log("MediaStore", "媒体目录数量=" + entries.size()
                    + "，错误=" + errors.size());
            for (Map.Entry<Long, String> entry : entries) {
                log("MediaStore", "目录: " + entry.getValue()
                        + " (bucketId=" + entry.getKey() + ")");
            }

            final List<String> finalErrors = errors;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                mediaButton.setEnabled(true);

                if (entries.isEmpty()) {
                    String detail = finalErrors.isEmpty()
                            ? getString(R.string.no_media_dirs_detail)
                            : getString(R.string.scan_error_detail, finalErrors.get(0));
                    log("MediaStore", "没有可用媒体目录: " + detail);
                    setStatus(detail, true);
                    if (!finalErrors.isEmpty()) {
                        for (String error : finalErrors) {
                            log("MediaStore", "错误: " + error);
                        }
                    }
                    return;
                }

                setStatus(getString(R.string.media_dirs_found, entries.size()), false);
                String[] names = new String[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    names[i] = entries.get(i).getValue();
                }

                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.pick_media_title)
                        .setItems(names, (d, which) -> {
                            Map.Entry<Long, String> entry = entries.get(which);
                            usingMediaBucket = true;
                            mediaBucketId = entry.getKey();
                            mediaBucketName = entry.getValue();
                            treeUri = null;

                            rows.clear();
                            adapter.notifyDataSetChanged();
                            pathView.setText(getString(
                                    R.string.media_selected_path, mediaBucketName));
                            setStatus(getString(R.string.ready_to_scan), false);
                            playButton.setEnabled(true);
                            playButton.requestFocus();
                            log("Picker", "已选择媒体目录: " + mediaBucketName
                                    + " bucketId=" + mediaBucketId);
                        })
                        .setNegativeButton(R.string.cancel,
                                (d, which) -> log("Picker", "用户取消媒体目录选择"))
                        .setOnDismissListener(d -> {
                            log("MediaStore", "媒体目录对话框关闭");
                            refreshSelectionStatus();
                        })
                        .create();

                dialog.setOnShowListener(d -> {
                    ListView list = dialog.getListView();
                    if (list != null) {
                        list.setSelection(0);
                        list.post(list::requestFocus);
                    }
                });
                dialog.show();
            });
        }, "media-buckets").start();
    }

    private void collectBuckets(Uri collection, Map<Long, String> buckets) {
        String[] projection = new String[]{COL_BUCKET_ID, COL_BUCKET_NAME};
        log("MediaStore", "查询目录 collection=" + collection
                + " projection=[" + COL_BUCKET_ID + ", " + COL_BUCKET_NAME + "]");

        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(collection, projection, null, null, null);
            if (cursor == null) {
                throw new IllegalStateException("ContentResolver.query 返回 null");
            }
            log("MediaStore", "目录 cursor rows=" + cursor.getCount());

            int idIndex = cursor.getColumnIndexOrThrow(COL_BUCKET_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(COL_BUCKET_NAME);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                String name = cursor.getString(nameIndex);
                if (name == null || name.length() == 0) {
                    name = "未知目录";
                }
                buckets.put(id, name);
                log("MediaStore", "发现目录: " + name + " bucketId=" + id);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private void refreshSelectionStatus() {
        if (treeUri != null || usingMediaBucket) {
            setStatus(getString(R.string.ready_to_scan), false);
        } else {
            setStatus(getString(R.string.select_hint), false);
        }
    }

    // ------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------

    private void setScanning(boolean scanning) {
        pickButton.setEnabled(!scanning);
        mediaButton.setEnabled(!scanning);
        playButton.setEnabled(!scanning);
        if (scanning) {
            setStatus(getString(R.string.scanning), false);
        }
    }

    private void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }

    private String describeTree(Uri uri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            int colon = documentId.indexOf(':');
            String path = colon >= 0 ? documentId.substring(colon + 1) : documentId;
            return path.length() == 0 ? "存储根目录" : path;
        } catch (Exception e) {
            logError("SAF", "解析目录路径失败: " + uri, e);
            return uri.toString();
        }
    }

    private Uri imageCollection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    private Uri videoCollection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        }
        return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    private static boolean isVideo(String mime, String name) {
        if (mime != null && mime.startsWith("video/")) {
            return true;
        }
        switch (extension(name)) {
            case "mp4":
            case "mkv":
            case "avi":
            case "mov":
            case "webm":
            case "m4v":
            case "ts":
            case "m2ts":
            case "mpg":
            case "mpeg":
            case "3gp":
            case "flv":
            case "wmv":
                return true;
            default:
                return false;
        }
    }

    private static boolean isImage(String mime, String name) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        switch (extension(name)) {
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "webp":
            case "bmp":
            case "heic":
            case "heif":
            case "avif":
            case "tif":
            case "tiff":
                return true;
            default:
                return false;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        log("App", "Activity 销毁");
        super.onDestroy();
    }
}
