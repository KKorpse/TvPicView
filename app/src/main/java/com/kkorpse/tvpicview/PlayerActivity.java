package com.kkorpse.tvpicview;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends Activity {

    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_IS_VIDEO = "extra_is_video";

    private static final String TAG = "TvPicView";
    private static final int MAX_IMAGE_WIDTH = 3840;
    private static final int MAX_IMAGE_HEIGHT = 2160;
    private static final long SEEK_STEP_MS = 10000L;
    private static final long OVERLAY_DISPLAY_MS = 4000L;
    private static final long ERROR_DISPLAY_MS = 5000L;
    private static final long ERROR_ADVANCE_MS = 6000L;

    private PlayerView playerView;
    private ImageView imageView;
    private ProgressBar progressBar;
    private TextView errorView;
    private View overlay;
    private TextView titleView;
    private TextView statusView;
    private TextView hintView;

    private ExoPlayer player;
    private boolean isVideo;
    private boolean hasError;
    private String videoSize;
    private String fileName = "";

    private List<PlaybackQueue.Item> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private String directoryKey = "";
    private boolean randomMode;
    private long imageDurationMs;
    private long imageRemainingMs;
    private long imageShownAt;
    private boolean imagePaused;
    private int imageWidth;
    private int imageHeight;
    private int loadToken;

    private final List<Integer> playOrder = new ArrayList<>();
    private int orderPosition = -1;
    private final Random random = new Random();
    private boolean playbackFinished;
    private boolean videoPaused;

    private final Map<String, String> actionStates = new HashMap<>();
    private ActionStateStore.Target actionFile;
    private ExecutorService actionWriter;

    private PlaybackQueue.Item lastDeletedItem;
    private String lastDeletedPreviousState;
    private long deleteUndoDeadline;

    private boolean exitDialogShowing;
    private boolean wasVideoPlayingBeforeExit;
    private boolean wasImagePausedBeforeExit;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideOverlayRunnable = () -> {
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
        if (hintView != null) {
            hintView.setVisibility(View.GONE);
        }
    };
    private final Runnable hideErrorRunnable = () -> {
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
    };
    private final Runnable imageAdvanceRunnable = () -> {
        if (!playbackFinished && !hasError) {
            advanceToNext();
        }
    };
    private final Runnable errorAdvanceRunnable = () -> {
        if (!playbackFinished) {
            advanceToNext();
        }
    };
    private final Runnable saveStateRunnable = new Runnable() {
        @Override
        public void run() {
            saveState();
            handler.postDelayed(this, PlaybackSettings.SAVE_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        playerView = findViewById(R.id.playerView);
        imageView = findViewById(R.id.imageView);
        progressBar = findViewById(R.id.progressBar);
        errorView = findViewById(R.id.errorView);
        overlay = findViewById(R.id.overlay);
        titleView = findViewById(R.id.titleView);
        statusView = findViewById(R.id.statusView);
        hintView = findViewById(R.id.hintView);

        setupPlaylist();
        randomMode = PlaybackSettings.isRandom(this);
        imageDurationMs = Math.max(1000L,
                PlaybackSettings.getImageDurationSeconds(this) * 1000L);
        actionWriter = Executors.newSingleThreadExecutor();
        loadActionFile();

        log("打开播放列表 total=" + playlist.size()
                + " start=" + currentIndex
                + " random=" + randomMode
                + " imageDuration=" + imageDurationMs + "ms"
                + " directory=" + directoryKey
                + " actionStates=" + actionStates.size());
        logDisplayModes();

        buildPlayOrder();
        if (playlist.isEmpty() || currentIndex < 0 || playOrder.isEmpty()) {
            showError(getString(R.string.player_no_uri),
                    getString(R.string.action_no_playable));
            return;
        }

        startCurrent();
    }

    private void loadActionFile() {
        try {
            actionFile = ActionStateStore.findOrCreate(this,
                    directoryKey,
                    PlaybackQueue.getTreeUri(),
                    PlaybackQueue.getVolumeName(),
                    PlaybackQueue.getRelativePath(),
                    PlaybackQueue.getDirectoryPath());
            actionStates.putAll(ActionStateStore.read(this, actionFile));
            log("状态文件=" + actionFile.describe()
                    + " inDirectory=" + actionFile.inDirectory
                    + " entries=" + actionStates.size());
            if (!actionFile.inDirectory) {
                Toast.makeText(this,
                        "目录不可写，状态文件已保存到应用私有目录",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable e) {
            logError("打开状态文件失败（收藏/删除标记将不可用）", e);
            Toast.makeText(this,
                    getString(R.string.action_error, errorSummary(e)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean ensureActionFile() {
        if (actionFile != null) {
            return true;
        }
        try {
            actionFile = ActionStateStore.findOrCreate(this,
                    directoryKey,
                    PlaybackQueue.getTreeUri(),
                    PlaybackQueue.getVolumeName(),
                    PlaybackQueue.getRelativePath(),
                    PlaybackQueue.getDirectoryPath());
            actionStates.putAll(ActionStateStore.read(this, actionFile));
            log("状态文件已就绪=" + actionFile.describe()
                    + " inDirectory=" + actionFile.inDirectory);
            if (!actionFile.inDirectory) {
                Toast.makeText(this,
                        "目录不可写，状态文件已保存到应用私有目录",
                        Toast.LENGTH_LONG).show();
            }
            return true;
        } catch (Throwable e) {
            logError("创建状态文件失败", e);
            showTransientError(getString(R.string.action_error,
                    errorSummary(e)), "");
            return false;
        }
    }

    private void buildPlayOrder() {
        playOrder.clear();
        List<Integer> available = new ArrayList<>();
        for (int i = 0; i < playlist.size(); i++) {
            if (!isDeleted(playlist.get(i))) {
                available.add(i);
            }
        }
        if (available.isEmpty()) {
            currentIndex = -1;
            return;
        }

        int start = currentIndex;
        if (!available.contains(start)) {
            start = available.get(0);
            for (int index : available) {
                if (index >= currentIndex) {
                    start = index;
                    break;
                }
            }
        }

        if (randomMode) {
            List<Integer> rest = new ArrayList<>(available);
            rest.remove(Integer.valueOf(start));
            Collections.shuffle(rest, random);
            playOrder.add(start);
            playOrder.addAll(rest);
        } else {
            int position = available.indexOf(start);
            for (int i = 0; i < available.size(); i++) {
                playOrder.add(available.get((position + i) % available.size()));
            }
        }

        orderPosition = 0;
        currentIndex = playOrder.get(0);
        StringBuilder order = new StringBuilder();
        for (int index : playOrder) {
            if (order.length() > 0) {
                order.append(" -> ");
            }
            order.append(playlist.get(index).name);
        }
        log("播放顺序 random=" + randomMode + ": " + order);
    }

    private void setupPlaylist() {
        List<PlaybackQueue.Item> queued = PlaybackQueue.getItems();
        if (!queued.isEmpty()) {
            playlist = new ArrayList<>(queued);
            directoryKey = PlaybackQueue.getDirectoryKey();
            currentIndex = clampIndex(PlaybackQueue.getStartIndex());
            return;
        }

        Uri uri = getIntent().getData();
        String name = getIntent().getStringExtra(EXTRA_NAME);
        boolean video = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);
        if (name == null || name.length() == 0) {
            name = uri == null ? "(未知文件)" : uri.getLastPathSegment();
        }
        if (uri != null) {
            playlist.add(new PlaybackQueue.Item(name, uri, video));
            directoryKey = "single";
            currentIndex = 0;
        }
    }

    private int clampIndex(int index) {
        if (playlist.isEmpty()) {
            return -1;
        }
        return Math.max(0, Math.min(index, playlist.size() - 1));
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void logDisplayModes() {
        try {
            Display display = getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            log("display current=" + current.getPhysicalWidth() + "x"
                    + current.getPhysicalHeight() + " @" + current.getRefreshRate());
            for (Display.Mode mode : display.getSupportedModes()) {
                log("display supported=" + mode.getPhysicalWidth() + "x"
                        + mode.getPhysicalHeight() + " @" + mode.getRefreshRate());
            }
        } catch (Exception e) {
            logError("读取显示模式失败", e);
        }
    }

    // ------------------------------------------------------------
    // 播放列表
    // ------------------------------------------------------------

    private void startCurrent() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            finishPlaylist();
            return;
        }

        PlaybackQueue.Item item = playlist.get(currentIndex);
        fileName = item.name;
        isVideo = item.video;
        hasError = false;
        playbackFinished = false;
        videoPaused = false;
        imagePaused = false;
        imageRemainingMs = imageDurationMs;
        imageShownAt = 0L;
        imageWidth = 0;
        imageHeight = 0;
        loadToken++;
        videoSize = null;

        handler.removeCallbacks(imageAdvanceRunnable);
        handler.removeCallbacks(errorAdvanceRunnable);
        handler.removeCallbacks(hideErrorRunnable);
        releasePlayer();
        imageView.setImageDrawable(null);
        imageView.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        hideLoading();

        titleView.setText(item.name);
        hintView.setText(isVideo
                ? R.string.player_hint_video
                : R.string.player_hint_image);
        showOverlayTemporary();
        saveState();

        log("开始第 " + (currentIndex + 1) + "/" + playlist.size()
                + " 个: " + item.name + " video=" + item.video
                + " uri=" + item.uri);

        if (item.uri == null) {
            showError(getString(R.string.player_no_uri),
                    getString(R.string.player_no_uri_detail));
            scheduleAdvanceAfterError();
            return;
        }

        if (item.video) {
            playVideo(item);
        } else {
            loadImage(item);
        }
    }

    private void advanceToNext() {
        if (playbackFinished) {
            return;
        }
        handler.removeCallbacks(imageAdvanceRunnable);
        handler.removeCallbacks(errorAdvanceRunnable);

        int nextPosition = orderPosition + 1;
        while (nextPosition < playOrder.size()
                && isDeleted(playlist.get(playOrder.get(nextPosition)))) {
            nextPosition++;
        }
        if (nextPosition >= playOrder.size()) {
            finishPlaylist();
            return;
        }

        orderPosition = nextPosition;
        currentIndex = playOrder.get(nextPosition);
        startCurrent();
    }

    private void switchItem(int direction) {
        if (playOrder.size() <= 1) {
            return;
        }
        int nextPosition = orderPosition;
        for (int i = 0; i < playOrder.size(); i++) {
            nextPosition = (nextPosition + direction + playOrder.size())
                    % playOrder.size();
            int nextIndex = playOrder.get(nextPosition);
            if (!isDeleted(playlist.get(nextIndex))) {
                playbackFinished = false;
                orderPosition = nextPosition;
                currentIndex = nextIndex;
                log("左右切换 direction=" + direction
                        + " orderPosition=" + nextPosition
                        + " name=" + playlist.get(nextIndex).name);
                startCurrent();
                return;
            }
        }
        finishPlaylist();
    }

    private boolean isDeleted(PlaybackQueue.Item item) {
        return ActionStateStore.DELETED.equals(actionStates.get(item.name));
    }

    private void finishPlaylist() {
        playbackFinished = true;
        handler.removeCallbacks(imageAdvanceRunnable);
        handler.removeCallbacks(errorAdvanceRunnable);
        hideLoading();
        setStatus(getString(R.string.player_ended), false);
        showOverlayPinned();
        saveState();
        log("播放列表结束");
    }

    private void saveState() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            PlaybackSettings.saveState(this, directoryKey,
                    playlist.get(currentIndex), currentIndex);
        }
    }

    private void scheduleAdvanceAfterError() {
        handler.removeCallbacks(errorAdvanceRunnable);
        if (!playbackFinished) {
            handler.postDelayed(errorAdvanceRunnable, ERROR_ADVANCE_MS);
        }
    }

    private void scheduleImageAdvance(long delayMs) {
        handler.removeCallbacks(imageAdvanceRunnable);
        if (playbackFinished || hasError || imagePaused) {
            return;
        }
        imageRemainingMs = Math.max(500L, delayMs);
        imageShownAt = SystemClock.elapsedRealtime();
        handler.postDelayed(imageAdvanceRunnable, imageRemainingMs);
    }

    private void pauseImageTimer() {
        if (imagePaused) {
            return;
        }
        if (imageShownAt > 0L) {
            long elapsed = SystemClock.elapsedRealtime() - imageShownAt;
            imageRemainingMs = Math.max(500L, imageRemainingMs - elapsed);
        }
        imagePaused = true;
        handler.removeCallbacks(imageAdvanceRunnable);
    }

    private void toggleImagePause() {
        if (hasError || playbackFinished) {
            return;
        }
        if (imagePaused) {
            imagePaused = false;
            if (imageWidth > 0 && imageHeight > 0) {
                setStatus(getString(R.string.player_image_info,
                        imageWidth, imageHeight), false);
            } else {
                setStatus(getString(R.string.player_playing), false);
            }
            showOverlayTemporary();
            scheduleImageAdvance(imageRemainingMs);
        } else {
            pauseImageTimer();
            setStatus(getString(R.string.player_paused), false);
            showOverlayPinned();
            log("图片暂停 remaining=" + imageRemainingMs + "ms");
        }
    }

    // ------------------------------------------------------------
    // 视频
    // ------------------------------------------------------------

    private void playVideo(PlaybackQueue.Item item) {
        playerView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
        showLoading();
        final int itemIndex = currentIndex;

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(), true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (itemIndex != currentIndex || hasError) {
                    return;
                }
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        showLoading();
                        setStatus(getString(R.string.player_buffering), false);
                        showOverlayTemporary();
                        break;
                    case Player.STATE_READY:
                        hideLoading();
                        videoSize = detectVideoSize(player.getCurrentTracks());
                        if (player.isPlaying()) {
                            setStatus(playingText(), false);
                            showOverlayTemporary();
                        } else {
                            setStatus(getString(R.string.player_paused), false);
                            showOverlayPinned();
                        }
                        break;
                    case Player.STATE_ENDED:
                        hideLoading();
                        advanceToNext();
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (itemIndex != currentIndex || hasError) {
                    return;
                }
                if (isPlaying) {
                    hideLoading();
                    setStatus(playingText(), false);
                    showOverlayTemporary();
                } else if (player != null
                        && player.getPlaybackState() != Player.STATE_ENDED) {
                    setStatus(getString(R.string.player_paused), false);
                    showOverlayPinned();
                }
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                if (itemIndex != currentIndex) {
                    return;
                }
                String size = detectVideoSize(tracks);
                if (size != null) {
                    videoSize = size;
                    log("视频源分辨率=" + size);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (itemIndex != currentIndex) {
                    return;
                }
                hideLoading();
                String detail = videoErrorDetail(error);
                logError("视频播放失败: " + detail, error);
                showError(getString(R.string.player_video_error), detail);
                scheduleAdvanceAfterError();
            }
        });

        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(item.uri));
        player.prepare();
        player.play();
        setStatus(getString(R.string.player_buffering), false);
        showOverlayTemporary();
        log("开始播放视频 name=" + item.name + " uri=" + item.uri);
    }

    private String playingText() {
        if (videoSize == null) {
            return getString(R.string.player_playing);
        }
        return getString(R.string.player_playing_info, videoSize);
    }

    private String videoErrorDetail(PlaybackException error) {
        StringBuilder detail = new StringBuilder();
        detail.append(error.getErrorCodeName())
                .append(" (").append(error.errorCode).append(")");
        if (error.getMessage() != null) {
            detail.append("\n").append(error.getMessage());
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            detail.append("\n").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                detail.append(": ").append(cause.getMessage());
            }
        }
        return detail.toString();
    }

    private String detectVideoSize(Tracks tracks) {
        if (tracks == null) {
            return null;
        }
        try {
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_VIDEO) {
                    continue;
                }
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    if (format.width > 0 && format.height > 0) {
                        return format.width + "×" + format.height;
                    }
                }
            }
        } catch (Exception e) {
            logError("读取视频分辨率失败", e);
        }
        return null;
    }

    private void togglePlayPause() {
        if (player == null || hasError || playbackFinished) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            videoPaused = true;
            setStatus(getString(R.string.player_paused), false);
            showOverlayPinned();
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0);
            }
            player.play();
            videoPaused = false;
            setStatus(playingText(), false);
            showOverlayTemporary();
        }
    }

    private void seekBy(long deltaMs) {
        if (player == null || hasError || playbackFinished) {
            return;
        }
        long duration = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (duration != C.TIME_UNSET) {
            target = Math.min(target, duration);
        }
        target = Math.max(0, target);
        player.seekTo(target);
        setStatus(getString(deltaMs > 0
                ? R.string.player_seek_forward
                : R.string.player_seek_back), false);
        showOverlayTemporary();
        log("seek delta=" + deltaMs + " target=" + target);
    }

    // ------------------------------------------------------------
    // 收藏 / 删除标记
    // ------------------------------------------------------------

    private void favoriteCurrent() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        PlaybackQueue.Item item = playlist.get(currentIndex);
        String state = actionStates.get(item.name);
        if (ActionStateStore.FAVORITE.equals(state)) {
            setStatus(getString(R.string.action_already_favorite), false);
            showOverlayPinned();
            return;
        }
        if (ActionStateStore.DELETED.equals(state)) {
            setStatus(getString(R.string.action_already_deleted), true);
            showOverlayTemporary();
            return;
        }
        if (!ensureActionFile()) {
            return;
        }

        actionStates.put(item.name, ActionStateStore.FAVORITE);
        saveActionStates();
        String text = getString(R.string.action_favorited, item.name);
        setStatus(text, false);
        showOverlayTemporary();
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        log("映射表: " + item.name + " -> 收藏");
    }

    private void deleteCurrent() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        PlaybackQueue.Item item = playlist.get(currentIndex);
        if (ActionStateStore.DELETED.equals(actionStates.get(item.name))) {
            setStatus(getString(R.string.action_already_deleted), true);
            showOverlayTemporary();
            return;
        }
        if (!ensureActionFile()) {
            return;
        }

        lastDeletedItem = item;
        lastDeletedPreviousState = actionStates.get(item.name);
        deleteUndoDeadline = SystemClock.elapsedRealtime() + 5000L;

        actionStates.put(item.name, ActionStateStore.DELETED);
        saveActionStates();
        log("映射表: " + item.name + " -> 删除；5 秒内返回键可撤回");
        advanceToNext();
    }

    private boolean canUndoDelete() {
        return lastDeletedItem != null
                && SystemClock.elapsedRealtime() <= deleteUndoDeadline;
    }

    private void undoDelete() {
        PlaybackQueue.Item item = lastDeletedItem;
        String previousState = lastDeletedPreviousState;
        lastDeletedItem = null;
        lastDeletedPreviousState = null;
        deleteUndoDeadline = 0L;

        if (item == null) {
            return;
        }
        if (previousState == null) {
            actionStates.remove(item.name);
        } else {
            actionStates.put(item.name, previousState);
        }
        saveActionStates();
        log("撤回删除: " + item.name + " previous=" + previousState);

        int index = -1;
        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).name.equals(item.name)) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            currentIndex = index;
            int position = playOrder.indexOf(index);
            if (position < 0) {
                buildPlayOrder();
            } else {
                orderPosition = position;
            }
            playbackFinished = false;
            hasError = false;
            startCurrent();
        }
    }

    private void showExitConfirm() {
        if (exitDialogShowing || isFinishing()) {
            return;
        }
        exitDialogShowing = true;
        wasVideoPlayingBeforeExit = isVideo && player != null && player.isPlaying();
        wasImagePausedBeforeExit = imagePaused;

        if (isVideo && player != null) {
            player.pause();
        }
        if (!isVideo) {
            pauseImageTimer();
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_exit_confirm, null);
        Button cancelButton = content.findViewById(R.id.exitCancelButton);
        Button returnButton = content.findViewById(R.id.exitReturnButton);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .create();
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        returnButton.setOnClickListener(v -> {
            exitDialogShowing = false;
            dialog.dismiss();
            finish();
        });
        dialog.setOnDismissListener(d -> {
            boolean shouldResume = exitDialogShowing;
            exitDialogShowing = false;
            if (shouldResume && !isFinishing()) {
                resumeAfterExitCancel();
            }
        });
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d ->
                cancelButton.postDelayed(cancelButton::requestFocus, 80));
        dialog.show();
    }

    private void resumeAfterExitCancel() {
        if (isVideo) {
            if (wasVideoPlayingBeforeExit && player != null && !hasError) {
                player.play();
                videoPaused = false;
            }
        } else if (!wasImagePausedBeforeExit && !playbackFinished && !hasError) {
            imagePaused = false;
            scheduleImageAdvance(imageRemainingMs);
        }
    }

    private void saveActionStates() {
        if (actionFile == null) {
            showTransientError(getString(R.string.action_error, "状态文件不可用"), "");
            return;
        }
        final ActionStateStore.Target target = actionFile;
        final String key = directoryKey;
        final Map<String, String> snapshot = new HashMap<>(actionStates);
        if (actionWriter == null) {
            actionWriter = Executors.newSingleThreadExecutor();
        }
        actionWriter.execute(() -> {
            try {
                ActionStateStore.write(this, target, key, snapshot);
                log("状态映射已写入 " + target.describe() + " entries=" + snapshot.size());
            } catch (Throwable e) {
                logError("写入状态映射失败", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showTransientError(
                                getString(R.string.action_error, errorSummary(e)), "");
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------
    // 图片
    // ------------------------------------------------------------

    private void loadImage(PlaybackQueue.Item item) {
        playerView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        showLoading();
        setStatus(getString(R.string.player_loading_image), false);
        showOverlayTemporary();
        final int token = loadToken;

        try {
            log("图片 MIME=" + getContentResolver().getType(item.uri)
                    + " uri=" + item.uri);
        } catch (Exception e) {
            logError("读取图片 MIME 失败", e);
        }

        new Thread(() -> {
            try {
                Bitmap bitmap = decodeImage(item.uri);
                if (bitmap == null) {
                    throw new IllegalStateException("Bitmap 解码返回 null");
                }
                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing() || isDestroyed()) {
                        bitmap.recycle();
                        return;
                    }
                    hideLoading();
                    imageView.setImageBitmap(bitmap);
                    imageWidth = bitmap.getWidth();
                    imageHeight = bitmap.getHeight();
                    if (imagePaused) {
                        setStatus(getString(R.string.player_paused), false);
                        showOverlayPinned();
                    } else {
                        setStatus(getString(R.string.player_image_info,
                                imageWidth, imageHeight), false);
                        showOverlayTemporary();
                        scheduleImageAdvance(imageDurationMs);
                    }
                    log("图片显示解码后=" + imageWidth + "x" + imageHeight);
                });
            } catch (Throwable e) {
                logError("图片加载失败", e);
                runOnUiThread(() -> {
                    if (token != loadToken || isFinishing() || isDestroyed()) {
                        return;
                    }
                    hideLoading();
                    showError(getString(R.string.player_image_error), imageErrorDetail(e));
                    scheduleAdvanceAfterError();
                });
            }
        }, "image-decode").start();
    }

    private String imageErrorDetail(Throwable error) {
        StringBuilder detail = new StringBuilder(errorSummary(error));
        if (fileName != null && fileName.length() > 0) {
            detail.append("\n").append(getString(R.string.player_file_detail, fileName));
        }
        if (isHeif(fileName)) {
            detail.append("\n").append(getString(R.string.player_heif_hint));
        }
        return detail.toString();
    }

    private Bitmap decodeImage(Uri uri) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            final int[] sourceSize = new int[]{0, 0};
            Bitmap bitmap = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                Size size = info.getSize();
                sourceSize[0] = size.getWidth();
                sourceSize[1] = size.getHeight();
                int[] target = fitWithin(size.getWidth(), size.getHeight());
                if (target[0] != size.getWidth() || target[1] != size.getHeight()) {
                    decoder.setTargetSize(target[0], target[1]);
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            log("图片源=" + sourceSize[0] + "x" + sourceSize[1]
                    + " 解码后=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            return bitmap;
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalArgumentException("无法读取图片尺寸");
        }

        int sample = 1;
        while ((bounds.outWidth / sample) > MAX_IMAGE_WIDTH
                || (bounds.outHeight / sample) > MAX_IMAGE_HEIGHT) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        log("图片源=" + bounds.outWidth + "x" + bounds.outHeight
                + " sample=" + sample + " 解码后="
                + (bitmap == null ? "null" : bitmap.getWidth() + "x" + bitmap.getHeight()));
        return bitmap;
    }

    private int[] fitWithin(int width, int height) {
        double scale = Math.min(1.0,
                Math.min(MAX_IMAGE_WIDTH / (double) width,
                        MAX_IMAGE_HEIGHT / (double) height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        return new int[]{targetWidth, targetHeight};
    }

    private boolean isHeif(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    // ------------------------------------------------------------
    // 播放控制与覆盖层
    // ------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                if (canUndoDelete()) {
                    undoDelete();
                } else {
                    showExitConfirm();
                }
            }
            return true;
        }

        if (!isVideo) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    toggleImagePause();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                    if (imagePaused) {
                        toggleImagePause();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (!imagePaused) {
                        toggleImagePause();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    switchItem(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    switchItem(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    favoriteCurrent();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    deleteCurrent();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (player != null && !playbackFinished) {
                    player.play();
                    videoPaused = false;
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) {
                    player.pause();
                    videoPaused = true;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (videoPaused) {
                    seekBy(-SEEK_STEP_MS);
                } else {
                    switchItem(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (videoPaused) {
                    seekBy(SEEK_STEP_MS);
                } else {
                    switchItem(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                favoriteCurrent();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                deleteCurrent();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void showLoading() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        if (errorView != null) {
            errorView.setVisibility(View.GONE);
        }
    }

    private void hideLoading() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    private void showOverlayTemporary() {
        showOverlay();
        handler.removeCallbacks(hideOverlayRunnable);
        handler.postDelayed(hideOverlayRunnable, OVERLAY_DISPLAY_MS);
    }

    private void showOverlayPinned() {
        showOverlay();
        handler.removeCallbacks(hideOverlayRunnable);
    }

    private void showOverlay() {
        if (overlay != null) {
            overlay.setVisibility(View.VISIBLE);
        }
        if (hintView != null) {
            hintView.setVisibility(View.VISIBLE);
        }
    }

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(error
                ? getColor(R.color.error)
                : getColor(R.color.text_secondary));
    }

    private void showError(String title, String detail) {
        hasError = true;
        hideLoading();
        showErrorCard(title, detail);
    }

    private void showTransientError(String title, String detail) {
        hideLoading();
        showErrorCard(title, detail);
    }

    private void showErrorCard(String title, String detail) {
        String text = title + (detail == null || detail.length() == 0
                ? "" : "\n\n" + detail);
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
        setStatus(title, true);
        showOverlayTemporary();
        handler.removeCallbacks(hideErrorRunnable);
        handler.postDelayed(hideErrorRunnable, ERROR_DISPLAY_MS);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
        }
    }

    private void log(String message) {
        Log.i(TAG, "[Player] " + message);
    }

    private void logError(String message, Throwable error) {
        Log.e(TAG, "[Player] " + message, error);
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
                + (message == null ? "" : ": " + message) + where;
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.removeCallbacks(saveStateRunnable);
        handler.postDelayed(saveStateRunnable, PlaybackSettings.SAVE_INTERVAL_MS);
        if (!isVideo && currentIndex >= 0 && !playbackFinished
                && !imagePaused && imageView.getDrawable() != null) {
            scheduleImageAdvance(imageRemainingMs);
        }
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(saveStateRunnable);
        handler.removeCallbacks(imageAdvanceRunnable);
        if (!isVideo && !imagePaused && imageShownAt > 0L) {
            long elapsed = SystemClock.elapsedRealtime() - imageShownAt;
            imageRemainingMs = Math.max(500L, imageRemainingMs - elapsed);
        }
        saveState();
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
        if (actionWriter != null) {
            actionWriter.shutdown();
        }
        PlaybackQueue.clear();
        log("播放页销毁");
        super.onDestroy();
    }
}
