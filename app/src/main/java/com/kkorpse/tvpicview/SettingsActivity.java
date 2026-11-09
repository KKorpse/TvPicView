package com.kkorpse.tvpicview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private static final int[] IMAGE_DURATIONS = {3, 5, 10, 15, 30, 60};

    private Button durationButton;
    private Button orderButton;
    private Button resumeButton;
    private Button filterImagesButton;
    private Button filterVideosButton;
    private Button onlyFavoritesButton;
    private Button excludeFavoritesButton;
    private Button storagePermissionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        durationButton = findViewById(R.id.durationButton);
        orderButton = findViewById(R.id.orderButton);
        resumeButton = findViewById(R.id.resumeButton);
        filterImagesButton = findViewById(R.id.filterImagesButton);
        filterVideosButton = findViewById(R.id.filterVideosButton);
        onlyFavoritesButton = findViewById(R.id.onlyFavoritesButton);
        excludeFavoritesButton = findViewById(R.id.excludeFavoritesButton);
        storagePermissionButton = findViewById(R.id.storagePermissionButton);
        TextView statePathView = findViewById(R.id.statePathView);

        durationButton.setOnClickListener(v -> showDurationDialog());
        orderButton.setOnClickListener(v -> {
            PlaybackSettings.setRandom(this, !PlaybackSettings.isRandom(this));
            refreshButtons();
        });
        resumeButton.setOnClickListener(v -> {
            PlaybackSettings.setResume(this, !PlaybackSettings.isResume(this));
            refreshButtons();
        });
        filterImagesButton.setOnClickListener(v -> {
            PlaybackSettings.setFilterImages(this,
                    !PlaybackSettings.isFilterImages(this));
            refreshButtons();
        });
        filterVideosButton.setOnClickListener(v -> {
            PlaybackSettings.setFilterVideos(this,
                    !PlaybackSettings.isFilterVideos(this));
            refreshButtons();
        });
        onlyFavoritesButton.setOnClickListener(v -> {
            PlaybackSettings.setOnlyFavorites(this,
                    !PlaybackSettings.isOnlyFavorites(this));
            refreshButtons();
        });
        excludeFavoritesButton.setOnClickListener(v -> {
            PlaybackSettings.setExcludeFavorites(this,
                    !PlaybackSettings.isExcludeFavorites(this));
            refreshButtons();
        });
        storagePermissionButton.setOnClickListener(v -> openAllFilesSettings());

        statePathView.setText(getString(R.string.settings_state_file,
                PlaybackSettings.getStateFilePath(this)));
        refreshButtons();
        durationButton.post(durationButton::requestFocus);
    }

    private void showDurationDialog() {
        int current = PlaybackSettings.getImageDurationSeconds(this);
        int checked = 0;
        String[] labels = new String[IMAGE_DURATIONS.length];
        for (int i = 0; i < IMAGE_DURATIONS.length; i++) {
            labels[i] = getString(R.string.settings_duration_value, IMAGE_DURATIONS[i]);
            if (IMAGE_DURATIONS[i] == current) {
                checked = i;
            }
        }

        final int initialChecked = checked;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_duration_title)
                .setSingleChoiceItems(labels, initialChecked, (d, which) -> {
                    PlaybackSettings.setImageDurationSeconds(this, IMAGE_DURATIONS[which]);
                    refreshButtons();
                    d.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            ListView list = dialog.getListView();
            if (list != null) {
                list.setSelection(initialChecked);
                list.post(list::requestFocus);
            }
        });
        dialog.show();
    }

    private void refreshButtons() {
        durationButton.setText(getString(R.string.settings_duration_value,
                PlaybackSettings.getImageDurationSeconds(this)));
        orderButton.setText(PlaybackSettings.isRandom(this)
                ? R.string.settings_order_random
                : R.string.settings_order_sequence);
        boolean resume = PlaybackSettings.isResume(this);
        resumeButton.setText(resume
                ? R.string.settings_resume_on
                : R.string.settings_resume_off);
        applyToggleStyle(resumeButton, resume);

        boolean filterImages = PlaybackSettings.isFilterImages(this);
        filterImagesButton.setText(filterImages
                ? R.string.settings_filter_images_on
                : R.string.settings_filter_images_off);
        applyToggleStyle(filterImagesButton, filterImages);

        boolean filterVideos = PlaybackSettings.isFilterVideos(this);
        filterVideosButton.setText(filterVideos
                ? R.string.settings_filter_videos_on
                : R.string.settings_filter_videos_off);
        applyToggleStyle(filterVideosButton, filterVideos);

        boolean onlyFavorites = PlaybackSettings.isOnlyFavorites(this);
        onlyFavoritesButton.setText(onlyFavorites
                ? R.string.settings_only_favorites_on
                : R.string.settings_only_favorites_off);
        applyToggleStyle(onlyFavoritesButton, onlyFavorites);

        boolean excludeFavorites = PlaybackSettings.isExcludeFavorites(this);
        excludeFavoritesButton.setText(excludeFavorites
                ? R.string.settings_exclude_favorites_on
                : R.string.settings_exclude_favorites_off);
        applyToggleStyle(excludeFavoritesButton, excludeFavorites);

        boolean granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && Environment.isExternalStorageManager();
        storagePermissionButton.setText(granted
                ? R.string.settings_storage_granted
                : R.string.settings_storage_grant);
    }

    private void applyToggleStyle(Button button, boolean enabled) {
        button.setBackgroundResource(enabled
                ? R.drawable.btn_toggle_on
                : R.drawable.btn_toggle_off);
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "当前 Android 版本不支持该权限",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception second) {
                Toast.makeText(this, "系统没有所有文件访问设置页",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshButtons();
    }
}
