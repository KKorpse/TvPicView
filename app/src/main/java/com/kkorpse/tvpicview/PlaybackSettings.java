package com.kkorpse.tvpicview;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class PlaybackSettings {

    private static final String TAG = "TvPicView";
    private static final String PREFS = "tvpicview_settings";
    private static final String KEY_IMAGE_DURATION = "image_duration_seconds";
    private static final String KEY_RANDOM = "random_playback";
    private static final String KEY_RESUME = "resume_playback";
    private static final String KEY_LAST_DIR = "last_directory";
    private static final String KEY_LAST_NAME = "last_name";
    private static final String KEY_LAST_URI = "last_uri";
    private static final String KEY_LAST_INDEX = "last_index";
    private static final String KEY_LAST_SAVED = "last_saved_at";
    private static final String KEY_HAS_MEDIA_DIR = "has_media_dir";
    private static final String KEY_MEDIA_BUCKET_ID = "media_bucket_id";
    private static final String KEY_MEDIA_BUCKET_NAME = "media_bucket_name";
    private static final String KEY_MEDIA_VOLUME_NAME = "media_volume_name";
    private static final String KEY_MEDIA_RELATIVE_PATH = "media_relative_path";
    private static final String KEY_MEDIA_DIRECTORY_PATH = "media_directory_path";
    private static final String KEY_FILTER_IMAGES = "filter_images";
    private static final String KEY_FILTER_VIDEOS = "filter_videos";

    public static final int DEFAULT_IMAGE_DURATION = 5;
    public static final long SAVE_INTERVAL_MS = 10000L;

    private PlaybackSettings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getImageDurationSeconds(Context context) {
        int value = prefs(context).getInt(KEY_IMAGE_DURATION, DEFAULT_IMAGE_DURATION);
        return value >= 1 && value <= 3600 ? value : DEFAULT_IMAGE_DURATION;
    }

    public static void setImageDurationSeconds(Context context, int seconds) {
        boolean ok = prefs(context).edit().putInt(KEY_IMAGE_DURATION, seconds).commit();
        Log.i(TAG, "[Settings] 图片播放时间=" + seconds + " 秒 saved=" + ok);
    }

    public static boolean isRandom(Context context) {
        return prefs(context).getBoolean(KEY_RANDOM, false);
    }

    public static void setRandom(Context context, boolean random) {
        boolean ok = prefs(context).edit().putBoolean(KEY_RANDOM, random).commit();
        Log.i(TAG, "[Settings] 播放顺序=" + (random ? "随机" : "顺序")
                + " saved=" + ok);
    }

    public static boolean isResume(Context context) {
        return prefs(context).getBoolean(KEY_RESUME, true);
    }

    public static void setResume(Context context, boolean resume) {
        boolean ok = prefs(context).edit().putBoolean(KEY_RESUME, resume).commit();
        Log.i(TAG, "[Settings] 继续上次=" + resume + " saved=" + ok);
    }

    public static boolean isFilterImages(Context context) {
        return prefs(context).getBoolean(KEY_FILTER_IMAGES, false);
    }

    public static void setFilterImages(Context context, boolean filter) {
        boolean ok = prefs(context).edit().putBoolean(KEY_FILTER_IMAGES, filter).commit();
        Log.i(TAG, "[Settings] 过滤图片=" + filter + " saved=" + ok);
    }

    public static boolean isFilterVideos(Context context) {
        return prefs(context).getBoolean(KEY_FILTER_VIDEOS, false);
    }

    public static void setFilterVideos(Context context, boolean filter) {
        boolean ok = prefs(context).edit().putBoolean(KEY_FILTER_VIDEOS, filter).commit();
        Log.i(TAG, "[Settings] 过滤视频=" + filter + " saved=" + ok);
    }

    public static void saveMediaDirectory(Context context, long bucketId, String bucketName,
                                          String volumeName, String relativePath,
                                          String directoryPath) {
        prefs(context).edit()
                .putBoolean(KEY_HAS_MEDIA_DIR, true)
                .putLong(KEY_MEDIA_BUCKET_ID, bucketId)
                .putString(KEY_MEDIA_BUCKET_NAME, bucketName == null ? "" : bucketName)
                .putString(KEY_MEDIA_VOLUME_NAME, volumeName == null ? "" : volumeName)
                .putString(KEY_MEDIA_RELATIVE_PATH, relativePath == null ? "" : relativePath)
                .putString(KEY_MEDIA_DIRECTORY_PATH, directoryPath == null ? "" : directoryPath)
                .commit();
        Log.i(TAG, "[Directory] 保存媒体目录 bucketId=" + bucketId
                + " name=" + bucketName
                + " volume=" + volumeName
                + " relative=" + relativePath);
    }

    public static boolean hasMediaDirectory(Context context) {
        return prefs(context).getBoolean(KEY_HAS_MEDIA_DIR, false)
                && prefs(context).getString(KEY_MEDIA_BUCKET_NAME, "").length() > 0;
    }

    public static long getMediaBucketId(Context context) {
        return prefs(context).getLong(KEY_MEDIA_BUCKET_ID, Long.MIN_VALUE);
    }

    public static String getMediaBucketName(Context context) {
        return prefs(context).getString(KEY_MEDIA_BUCKET_NAME, "");
    }

    public static String getMediaVolumeName(Context context) {
        String value = prefs(context).getString(KEY_MEDIA_VOLUME_NAME, "");
        return value == null || value.length() == 0 ? null : value;
    }

    public static String getMediaRelativePath(Context context) {
        String value = prefs(context).getString(KEY_MEDIA_RELATIVE_PATH, "");
        return value == null || value.length() == 0 ? null : value;
    }

    public static String getMediaDirectoryPath(Context context) {
        String value = prefs(context).getString(KEY_MEDIA_DIRECTORY_PATH, "");
        return value == null || value.length() == 0 ? null : value;
    }

    public static int findResumeIndex(Context context, String directoryKey,
                                      List<PlaybackQueue.Item> items) {
        if (directoryKey == null || items == null || items.isEmpty()) {
            return -1;
        }
        SharedPreferences preferences = prefs(context);
        if (!directoryKey.equals(preferences.getString(KEY_LAST_DIR, ""))) {
            return -1;
        }

        String lastUri = preferences.getString(KEY_LAST_URI, "");
        for (int i = 0; i < items.size(); i++) {
            PlaybackQueue.Item item = items.get(i);
            if (item.uri != null && item.uri.toString().equals(lastUri)) {
                return i;
            }
        }

        String lastName = preferences.getString(KEY_LAST_NAME, "");
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name.equals(lastName)) {
                return i;
            }
        }
        return -1;
    }

    public static void saveState(Context context, String directoryKey,
                                 PlaybackQueue.Item item, int index) {
        if (item == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String uri = item.uri == null ? "" : item.uri.toString();

        prefs(context).edit()
                .putString(KEY_LAST_DIR, directoryKey == null ? "" : directoryKey)
                .putString(KEY_LAST_NAME, item.name)
                .putString(KEY_LAST_URI, uri)
                .putInt(KEY_LAST_INDEX, index)
                .putLong(KEY_LAST_SAVED, now)
                .apply();

        final String snapshot = "directory=" + (directoryKey == null ? "" : directoryKey)
                + "\nname=" + item.name
                + "\nuri=" + uri
                + "\nindex=" + index
                + "\nsaved_at=" + now
                + "\n";
        final Context appContext = context.getApplicationContext();
        new Thread(() -> writeMirror(appContext, snapshot), "state-save").start();

        Log.i(TAG, "[State] 保存播放状态 name=" + item.name
                + " index=" + index + " directory=" + directoryKey);
    }

    private static void writeMirror(Context context, String text) {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) {
            return;
        }
        File file = new File(directory, "playback_state.txt");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "[State] 写状态文件失败: " + file, e);
        }
    }

    public static String getStateFilePath(Context context) {
        File directory = context.getExternalFilesDir(null);
        if (directory == null) {
            return "不可用";
        }
        return new File(directory, "playback_state.txt").getAbsolutePath();
    }
}
