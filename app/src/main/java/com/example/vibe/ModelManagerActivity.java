package com.example.vibe;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

public class ModelManagerActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "EdgeImpulsePrefs";
    private static final String PREF_MODE  = "active_mode";
    private static final String MODE_NV    = "nonvoice";
    private static final String MODE_VC    = "voice";

    private MaterialButton btnModeSounds, btnModeVoice;
    private TextView tvSampleSummary;
    private TextView tvModelStatus;
    private ProgressBar pbModel;
    private MaterialButton btnTeachAlerts;
    private MaterialButton btnDeleteSamples;
    private TextView tvHintTeach;
    private TextView tvHintDelete;

    private String mode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_manager);

        bindViews();
        bindActions();

        applyTopInsetsToTopBar();
        applyBottomInsetsToContent();

        loadPrefs();
        applyModeUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPrefs();
        applyModeUi();
    }

    private void bindViews() {
        btnModeSounds    = findViewById(R.id.buttonModeNonVoiceModel);
        btnModeVoice     = findViewById(R.id.buttonModeVoiceModel);
        tvSampleSummary  = findViewById(R.id.textSampleSummaryModel);
        tvModelStatus    = findViewById(R.id.textModelStatus);
        pbModel          = findViewById(R.id.progressModel);
        btnTeachAlerts   = findViewById(R.id.buttonTeachAlertsModel);
        btnDeleteSamples = findViewById(R.id.buttonDeleteSamplesModel);
        tvHintTeach      = findViewById(R.id.textHintTeachModel);
        tvHintDelete     = findViewById(R.id.textHintDeleteModel);

        if (pbModel != null) {
            pbModel.setMax(100);
            pbModel.setProgress(0);
            pbModel.setVisibility(View.GONE);
        }
    }

    private void bindActions() {
        View backBtn = findViewById(R.id.buttonBackTopModel);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        if (btnModeSounds != null) btnModeSounds.setOnClickListener(v -> setMode(MODE_NV));
        if (btnModeVoice  != null) btnModeVoice.setOnClickListener(v -> setMode(MODE_VC));

        if (btnTeachAlerts != null) {
            btnTeachAlerts.setOnClickListener(v ->
                android.widget.Toast.makeText(this, "Starting teaching session…", android.widget.Toast.LENGTH_SHORT).show());
        }
        if (btnDeleteSamples != null) {
            btnDeleteSamples.setOnClickListener(v ->
                android.widget.Toast.makeText(this, "Removing samples…", android.widget.Toast.LENGTH_SHORT).show());
        }
    }

    private void loadPrefs() {
        mode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_MODE, MODE_NV);
        if (!MODE_VC.equals(mode)) mode = MODE_NV;
        if (tvSampleSummary != null)
            tvSampleSummary.setText(MODE_VC.equals(mode) ? "Name samples: 0 recorded" : "Sound samples: 0 recorded");
        if (tvModelStatus != null)
            tvModelStatus.setText("Ready to record. Tap 'Teach / update alerts' to start.");
    }

    private void setMode(String newMode) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_MODE, newMode).apply();
        loadPrefs();
        applyModeUi();
    }

    private void applyModeUi() {
        boolean v = MODE_VC.equals(mode);
        if (btnModeSounds != null) {
            btnModeSounds.setText("👂 Sounds");
            modeBtn(btnModeSounds, !v);
        }
        if (btnModeVoice != null) {
            btnModeVoice.setText("🗣️ My name");
            modeBtn(btnModeVoice, v);
        }
    }

    private void modeBtn(MaterialButton b, boolean active) {
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(active ? "#1A237E" : "#F2F4F8")));
        b.setTextColor(android.graphics.Color.parseColor(active ? "#FFFFFF" : "#6B7280"));
    }

    private void applyTopInsetsToTopBar() {
        View topBar = findViewById(R.id.topBarModel);
        if (topBar == null) return;
        final int bl = topBar.getPaddingLeft(), bt = topBar.getPaddingTop(),
                  br = topBar.getPaddingRight(), bb = topBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            v.setPadding(bl, bt + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, br, bb);
            return insets;
        });
        ViewCompat.requestApplyInsets(topBar);
    }

    private void applyBottomInsetsToContent() {
        View content = findViewById(R.id.modelContent);
        if (content == null) return;
        final int bl = content.getPaddingLeft(), bt = content.getPaddingTop(),
                  br = content.getPaddingRight(), bb = content.getPaddingBottom();
        final int extra = Math.round(12 * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(bl, bt, br, bb + bottomInset + extra);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
