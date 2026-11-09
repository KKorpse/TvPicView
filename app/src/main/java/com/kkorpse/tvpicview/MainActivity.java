package com.kkorpse.tvpicview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.BaseColumns;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends Activity {

    private static final String TAG = "TvPicView";
    private static final int REQ_MEDIA_PERM = 1002;
    private static final int MAX_FILES = 2000;
    private static final int MAX_LOG_LINES = 800;
    private static final int MAX_ERRORS = 50;

    private static final String COL_BUCKET_ID = "bucket_id";
    private static final String COL_BUCKET_NAME = "bucket_display_name";

    private TextView pathView;
    private TextView statusView;
    private TextView videoCountView;
    private TextView imageCountView;
    private TextView logButton;
    private View playButton;
    private View mediaButton;
    private View settingsButton;
    private View refreshButton;

    private boolean usingMediaBucket;
    private long mediaBucketId = Long.MIN_VALUE;
    private String mediaBucketName;
    private String mediaStoreVolumeName;
    private String mediaStoreRelativePath;
    private String mediaStoreDirectoryPath;
    private boolean scanning;
    private boolean focusPlayAfterPicker;

    private final Deque<String> diagnostics = new ArrayDeque<>();
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
        videoCountView = findViewById(R.id.videoCountView);
        imageCountView = findViewById(R.id.imageCountView);
        logButton = findViewById(R.id.logButton);
        playButton = findViewById(R.id.playButton);
        mediaButton = findViewById(R.id.mediaButton);
        settingsButton = findViewById(R.id.settingsButton);
        refreshButton = findViewById(R.id.refreshButton);

        playButton.setOnClickListener(v -> play());
        refreshButton.setOnClickListener(v -> refresh());
        mediaButton.setOnClickListener(v -> chooseMediaLibrary());
        settingsButton.setOnClickListener(v -> {
            log("Settings", "打开播放设置");
            startActivity(new Intent(this, SettingsActivity.class));
        });
        logButton.setOnClickListener(v -> showLogDialog());

        logEnvironment();
        restoreLastDirectory();
        playButton.post(playButton::requestFocus);
    }

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
        log("Settings", "duration=" + PlaybackSettings.getImageDurationSeconds(this)
                + "s random=" + PlaybackSettings.isRandom(this)
                + " resume=" + PlaybackSettings.isResume(this)
                + " filterImages=" + PlaybackSettings.isFilterImages(this)
                + " filterVideos=" + PlaybackSettings.isFilterVideos(this));
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
        log("App", "诊断日志已清空");
    }

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(getColor(error ? R.color.error : R.color.text_secondary));
    }

    private void clearStatus() {
        setStatus("", false);
    }

    private String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
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
    // 目录
    // ------------------------------------------------------------

    private void restoreLastDirectory() {
        if (!PlaybackSettings.hasMediaDirectory(this)) {
            usingMediaBucket = false;
            mediaBucketId = Long.MIN_VALUE;
            mediaBucketName = null;
            mediaStoreVolumeName = null;
            mediaStoreRelativePath = null;
            mediaStoreDirectoryPath = null;
            updateDirectoryView();
            setCounts(0, 0);
            clearStatus();
            log("Directory", "没有上次目录");
            return;
        }

        usingMediaBucket = true;
        mediaBucketId = PlaybackSettings.getMediaBucketId(this);
        mediaBucketName = PlaybackSettings.getMediaBucketName(this);
        mediaStoreVolumeName = PlaybackSettings.getMediaVolumeName(this);
        mediaStoreRelativePath = PlaybackSettings.getMediaRelativePath(this);
        mediaStoreDirectoryPath = PlaybackSettings.getMediaDirectoryPath(this);
        updateDirectoryView();
        setCounts(0, 0);
        clearStatus();
        log("Directory", "恢复上次目录 bucketId=" + mediaBucketId
                + " name=" + mediaBucketName
                + " volume=" + mediaStoreVolumeName
                + " relative=" + mediaStoreRelativePath
                + " path=" + mediaStoreDirectoryPath);
    }

    private void updateDirectoryView() {
        if (pathView == null) {
            return;
        }
        if (!usingMediaBucket) {
            pathView.setText(R.string.no_directory);
            pathView.setTextColor(getColor(R.color.text_secondary));
            return;
        }

        String address = mediaStoreDirectoryPath;
        if ((address == null || address.length() == 0)
                && mediaStoreVolumeName != null && mediaStoreRelativePath != null) {
            address = "/storage/" + mediaStoreVolumeName + "/" + mediaStoreRelativePath;
        }
        if (address != null && address.length() > 0) {
            pathView.setText(getString(R.string.directory_address, address));
        } else {
            pathView.setText(getString(R.string.media_selected_path,
                    mediaBucketName == null ? "媒体库" : mediaBucketName));
        }
        pathView.setTextColor(getColor(R.color.text_primary));
    }

    private void chooseMediaLibrary() {
        log("MediaStore", "打开媒体库目录选择");
        if (!hasMediaPermission()) {
            requestMediaPermission();
            return;
        }
        showMediaBucketDialog();
    }

    private void play() {
        if (usingMediaBucket) {
            log("Play", "点击播放 bucketId=" + mediaBucketId
                    + " name=" + mediaBucketName);
            startMediaBucketScan(true);
            return;
        }

        log("Play", "没有可用目录，跳转到媒体库目录选择");
        setStatus(getString(R.string.no_directory_hint), false);
        chooseMediaLibrary();
    }

    private void refresh() {
        if (usingMediaBucket) {
            log("Refresh", "刷新媒体库目录 bucketId=" + mediaBucketId
                    + " name=" + mediaBucketName);
            startMediaBucketScan(false);
            return;
        }
        log("Refresh", "没有可用目录，提示先选择目录");
        setStatus(getString(R.string.no_directory_hint), false);
        playButton.requestFocus();
    }

    private void startMediaBucketScan(boolean playAfterScan) {
        final long bucketId = mediaBucketId;
        final String bucketName = mediaBucketName;
        if (scanning) {
            log("Play", "正在扫描，忽略重复操作");
            return;
        }
        setScanning(true);
        setCounts(0, 0);
        boolean filterImages = PlaybackSettings.isFilterImages(this);
        boolean filterVideos = PlaybackSettings.isFilterVideos(this);
        boolean onlyFavorites = PlaybackSettings.isOnlyFavorites(this);
        boolean excludeFavorites = PlaybackSettings.isExcludeFavorites(this);
        log("MediaStore", "开始扫描媒体库目录: " + bucketName + " bucketId=" + bucketId
                + " filterImages=" + filterImages
                + " filterVideos=" + filterVideos
                + " onlyFavorites=" + onlyFavorites
                + " excludeFavorites=" + excludeFavorites);

        new Thread(() -> {
            List<PlaybackQueue.Item> found = new ArrayList<>();
            int[] counts = new int[]{0, 0};
            List<String> errors = new ArrayList<>();

            if (!filterVideos) {
                try {
                    collectBucketMedia(videoCollection(), true, bucketId, found, counts);
                } catch (Exception e) {
                    addError(errors, "视频 -> " + errorSummary(e));
                    logError("MediaStore", "读取视频媒体库失败", e);
                }
            } else {
                log("Filter", "已开启过滤视频，跳过视频扫描");
            }
            if (!filterImages) {
                try {
                    collectBucketMedia(imageCollection(), false, bucketId, found, counts);
                } catch (Exception e) {
                    addError(errors, "图片 -> " + errorSummary(e));
                    logError("MediaStore", "读取图片媒体库失败", e);
                }
            } else {
                log("Filter", "已开启过滤图片，跳过图片扫描");
            }

            Map<String, String> actionStates = new HashMap<>();
            if (!found.isEmpty()) {
                try {
                    ActionStateStore.Target target = ActionStateStore.findOrCreate(
                            this,
                            currentDirectoryKey(),
                            null,
                            mediaStoreVolumeName,
                            mediaStoreRelativePath,
                            mediaStoreDirectoryPath);
                    actionStates.putAll(ActionStateStore.read(this, target));
                    log("Filter", "状态映射 entries=" + actionStates.size()
                            + " file=" + target.describe());
                } catch (Throwable e) {
                    logError("Filter", "读取收藏/删除状态失败", e);
                }
            }

            List<PlaybackQueue.Item> filtered = new ArrayList<>();
            int[] filteredCounts = new int[]{0, 0};
            for (PlaybackQueue.Item item : found) {
                String state = actionStates.get(item.name);
                if (ActionStateStore.DELETED.equals(state)) {
                    continue;
                }
                if (onlyFavorites && !ActionStateStore.FAVORITE.equals(state)) {
                    continue;
                }
                if (!onlyFavorites && excludeFavorites
                        && ActionStateStore.FAVORITE.equals(state)) {
                    continue;
                }
                filtered.add(item);
                filteredCounts[item.video ? 0 : 1]++;
            }
            log("Filter", "原始=" + found.size() + " 过滤后=" + filtered.size()
                    + " 视频=" + filteredCounts[0] + " 图片=" + filteredCounts[1]);

            Collections.sort(filtered);
            logResults(bucketName == null ? "媒体库" : bucketName,
                    filtered, filteredCounts, errors);
            final List<PlaybackQueue.Item> finalFound = filtered;
            final int[] finalCounts = filteredCounts;
            final int errorCount = errors.size();
            final String firstError = errors.isEmpty() ? null : errors.get(0);
            runOnUiThread(() -> showScanResult(
                    finalFound, finalCounts, firstError, errorCount, playAfterScan));
        }, "media-scan").start();
    }

    private void collectBucketMedia(Uri collection, boolean video, long bucketId,
                                    List<PlaybackQueue.Item> out, int[] counts) {
        boolean hasPathColumns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
        String[] projection = hasPathColumns
                ? new String[]{
                BaseColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.DATA
        }
                : new String[]{
                BaseColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATA
        };
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
                long id = cursor.getLong(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                String volume = hasPathColumns ? cursor.getString(3) : null;
                String relative = hasPathColumns ? cursor.getString(4) : null;
                String dataPath = cursor.getString(hasPathColumns ? 5 : 3);
                if (mediaStoreVolumeName == null && volume != null) {
                    mediaStoreVolumeName = volume;
                }
                if (mediaStoreRelativePath == null && relative != null) {
                    mediaStoreRelativePath = relative;
                }
                if (mediaStoreDirectoryPath == null && dataPath != null) {
                    mediaStoreDirectoryPath = new File(dataPath).getParent();
                }
                if (name != null && !name.startsWith(ActionStateStore.FILE_NAME)) {
                    Uri uri = ContentUris.withAppendedId(collection, id);
                    out.add(new PlaybackQueue.Item(name, uri, video,
                            mime, volume, relative, null));
                    counts[video ? 0 : 1]++;
                    count++;
                    log("MediaStore", "文件: " + (video ? "[视频] " : "[图片] ")
                            + name + " uri=" + uri
                            + " volume=" + volume + " relative=" + relative
                            + " data=" + dataPath);
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

    private void showScanResult(List<PlaybackQueue.Item> found, int[] counts,
                                String error, int errorCount, boolean playAfterScan) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setScanning(false);
        setCounts(counts[0], counts[1]);
        updateDirectoryView();
        PlaybackSettings.saveMediaDirectory(this, mediaBucketId, mediaBucketName,
                mediaStoreVolumeName, mediaStoreRelativePath, mediaStoreDirectoryPath);

        if (error != null && found.isEmpty()) {
            log("Result", "扫描失败: " + error);
            setStatus(getString(R.string.scan_error_detail, error), true);
            return;
        }

        if (found.isEmpty()) {
            log("Result", "扫描完成: 0 个文件");
            setStatus(getString(R.string.scan_empty), false);
            playButton.requestFocus();
            return;
        }

        log("Result", "扫描完成: " + found.size() + " 个文件"
                + "，视频=" + counts[0] + "，图片=" + counts[1]
                + "，错误=" + errorCount);

        if (playAfterScan) {
            setStatus(getString(R.string.auto_playing), false);
            startScannedPlayback(found);
            return;
        }

        if (errorCount > 0) {
            setStatus(getString(R.string.scan_result_warning,
                    found.size(), counts[0], counts[1], errorCount), true);
        } else {
            setStatus(getString(R.string.refresh_done, counts[0], counts[1]), false);
        }
        playButton.requestFocus();
    }

    private void startScannedPlayback(List<PlaybackQueue.Item> items) {
        if (items.isEmpty()) {
            return;
        }

        boolean random = PlaybackSettings.isRandom(this);
        boolean resume = PlaybackSettings.isResume(this);
        int savedIndex = -1;
        int startIndex = 0;

        if (resume) {
            savedIndex = PlaybackSettings.findResumeIndex(
                    this, currentDirectoryKey(), items);
        }
        if (savedIndex >= 0) {
            startIndex = savedIndex;
        } else if (random) {
            startIndex = new Random().nextInt(items.size());
        }

        PlaybackQueue.setDirectoryContext(
                null, mediaStoreVolumeName, mediaStoreRelativePath,
                mediaStoreDirectoryPath);
        PlaybackQueue.set(items, currentDirectoryKey(), startIndex);

        PlaybackQueue.Item item = items.get(startIndex);
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.setData(item.uri);
        intent.putExtra(PlayerActivity.EXTRA_NAME, item.name);
        intent.putExtra(PlayerActivity.EXTRA_IS_VIDEO, item.video);

        log("Play", "开始播放 total=" + items.size()
                + " random=" + random
                + " resume=" + resume
                + " savedIndex=" + savedIndex
                + " startIndex=" + startIndex
                + " name=" + item.name);
        setStatus(getString(R.string.auto_playing), false);
        try {
            startActivity(intent);
        } catch (Exception e) {
            logError("Play", "打开播放页失败", e);
            setStatus(getString(R.string.player_error, errorSummary(e)), true);
        }
    }

    private String currentDirectoryKey() {
        return "bucket:" + mediaBucketId + ":" + mediaBucketName;
    }

    private void logResults(String directory, List<PlaybackQueue.Item> found,
                            int[] counts, List<String> errors) {
        log("Result", "目录: " + directory);
        log("Result", "文件总数=" + found.size()
                + "，视频=" + counts[0] + "，图片=" + counts[1]
                + "，目录错误=" + errors.size());
        for (PlaybackQueue.Item item : found) {
            Log.i(TAG, "[Result] " + (item.video ? "[视频] " : "[图片] ")
                    + item.name + " -> " + item.uri);
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
                            mediaStoreVolumeName = null;
                            mediaStoreRelativePath = null;
                            mediaStoreDirectoryPath = null;
                            updateDirectoryView();
                            setCounts(0, 0);
                            clearStatus();
                            focusPlayAfterPicker = true;
                            playButton.setEnabled(true);
                            playButton.requestFocus();
                            PlaybackSettings.saveMediaDirectory(
                                    MainActivity.this,
                                    mediaBucketId,
                                    mediaBucketName,
                                    mediaStoreVolumeName,
                                    mediaStoreRelativePath,
                                    mediaStoreDirectoryPath);
                            log("Picker", "已选择媒体目录: " + mediaBucketName
                                    + " bucketId=" + mediaBucketId
                                    + " volume=" + mediaStoreVolumeName
                                    + " relative=" + mediaStoreRelativePath);

                        })
                        .setNegativeButton(R.string.cancel,
                                (d, which) -> log("Picker", "用户取消媒体目录选择"))
                        .setOnDismissListener(d -> {
                            log("MediaStore", "媒体目录对话框关闭");
                            if (!scanning) {
                                refreshSelectionStatus();
                            }
                            if (focusPlayAfterPicker) {
                                focusPlayAfterPicker = false;
                                playButton.post(playButton::requestFocus);
                            }
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
        clearStatus();
    }

    // ------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------

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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQ_MEDIA_PERM);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
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

    // ------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------

    private void setCounts(int videos, int images) {
        videoCountView.setText(String.valueOf(videos));
        imageCountView.setText(String.valueOf(images));
    }

    private void setScanning(boolean value) {
        scanning = value;
        playButton.setEnabled(!value);
        mediaButton.setEnabled(!value);
        settingsButton.setEnabled(!value);
        refreshButton.setEnabled(!value);
        if (value) {
            setStatus(getString(R.string.scanning), false);
        }
    }

    private void addError(List<String> errors, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
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

    @Override
    protected void onResume() {
        super.onResume();
        if (playButton == null) {
            return;
        }
        if (!scanning) {
            refreshSelectionStatus();
        }
        playButton.post(playButton::requestFocus);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && playButton != null && !scanning) {
            playButton.postDelayed(playButton::requestFocus, 120);
        }
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
