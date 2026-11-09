package com.kkorpse.tvpicview;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

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

public class PlayerActivity extends Activity {

    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_IS_VIDEO = "extra_is_video";

    private static final String TAG = "TvPicView";
    private static final int MAX_IMAGE_WIDTH = 3840;
    private static final int MAX_IMAGE_HEIGHT = 2160;
    private static final long SEEK_STEP_MS = 10000L;
    private static final long OVERLAY_DISPLAY_MS = 4000L;
    private static final long ERROR_DISPLAY_MS = 5000L;

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

        Uri uri = getIntent().getData();
        String name = getIntent().getStringExtra(EXTRA_NAME);
        isVideo = getIntent().getBooleanExtra(EXTRA_IS_VIDEO, false);

        if (name == null || name.length() == 0) {
            name = uri == null ? "(未知文件)" : uri.getLastPathSegment();
        }
        fileName = name;
        titleView.setText(name);
        hintView.setText(isVideo ? R.string.player_hint_video : R.string.player_hint_image);

        log("打开 name=" + name + " isVideo=" + isVideo + " uri=" + uri);
        logDisplayModes();

        if (uri == null) {
            showError(getString(R.string.player_no_uri),
                    getString(R.string.player_no_uri_detail));
            return;
        }

        if (isVideo) {
            try {
                playVideo(uri);
            } catch (Throwable e) {
                logError("初始化视频播放器失败", e);
                showError(getString(R.string.player_video_error), errorSummary(e));
            }
        } else {
            loadImage(uri);
        }
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
    // 视频
    // ------------------------------------------------------------

    private void playVideo(Uri uri) {
        hasError = false;
        playerView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
        showLoading();

        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(), true);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (hasError) {
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
                        setStatus(getString(R.string.player_ended), false);
                        showOverlayPinned();
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (hasError) {
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
                String size = detectVideoSize(tracks);
                if (size != null) {
                    videoSize = size;
                    log("视频源分辨率=" + size);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                hideLoading();
                String detail = videoErrorDetail(error);
                logError("视频播放失败: " + detail, error);
                showError(getString(R.string.player_video_error), detail);
            }
        });

        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.play();
        setStatus(getString(R.string.player_buffering), false);
        showOverlayTemporary();
        log("开始播放 uri=" + uri);
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
        if (player == null || hasError) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            setStatus(getString(R.string.player_paused), false);
            showOverlayPinned();
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0);
            }
            player.play();
            setStatus(playingText(), false);
            showOverlayTemporary();
        }
    }

    private void seekBy(long deltaMs) {
        if (player == null || hasError) {
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
    // 图片
    // ------------------------------------------------------------

    private void loadImage(Uri uri) {
        hasError = false;
        playerView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        showLoading();
        setStatus(getString(R.string.player_loading_image), false);
        showOverlayTemporary();

        try {
            log("图片 MIME=" + getContentResolver().getType(uri) + " uri=" + uri);
        } catch (Exception e) {
            logError("读取图片 MIME 失败", e);
        }

        new Thread(() -> {
            try {
                Bitmap bitmap = decodeImage(uri);
                if (bitmap == null) {
                    throw new IllegalStateException("Bitmap 解码返回 null");
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    hideLoading();
                    imageView.setImageBitmap(bitmap);
                    setStatus(getString(R.string.player_image_info,
                            bitmap.getWidth(), bitmap.getHeight()), false);
                    showOverlayTemporary();
                    log("图片显示解码后=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                });
            } catch (Throwable e) {
                logError("图片加载失败", e);
                runOnUiThread(() -> {
                    hideLoading();
                    String detail = imageErrorDetail(e);
                    showError(getString(R.string.player_image_error), detail);
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
        if (!isVideo) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showOverlayTemporary();
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
                if (player != null) {
                    player.play();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) {
                    player.pause();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                seekBy(-SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                seekBy(SEEK_STEP_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showOverlayTemporary();
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
        String text = title + (detail == null || detail.length() == 0
                ? "" : "\n\n" + detail);
        errorView.setText(text);
        errorView.setVisibility(View.VISIBLE);
        setStatus(title, true);
        showOverlayTemporary();
        handler.removeCallbacks(hideErrorRunnable);
        handler.postDelayed(hideErrorRunnable, ERROR_DISPLAY_MS);
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
    protected void onStop() {
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideOverlayRunnable);
        handler.removeCallbacks(hideErrorRunnable);
        if (player != null) {
            player.release();
            player = null;
        }
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
        log("播放页销毁");
        super.onDestroy();
    }
}
