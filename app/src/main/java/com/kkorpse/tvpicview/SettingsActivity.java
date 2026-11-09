package com.kkorpse.tvpicview;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

public class SettingsActivity extends Activity {

    private static final int[] IMAGE_DURATIONS = {3, 5, 10, 15, 30, 60};

    private Button durationButton;
    private Button orderButton;
    private Button resumeButton;

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
        resumeButton.setText(PlaybackSettings.isResume(this)
                ? R.string.settings_resume_on
                : R.string.settings_resume_off);
    }
}
