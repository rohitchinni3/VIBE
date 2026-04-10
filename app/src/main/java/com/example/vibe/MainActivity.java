package com.example.vibe;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyTopInsetsToTopBar();
        applyBottomInsetsToContent();

        MaterialButton btnConnectWatch = findViewById(R.id.buttonHomeConnectWatch);
        MaterialButton btnTeachAlerts  = findViewById(R.id.buttonHomeCustomizeModel);

        if (btnConnectWatch != null) {
            btnConnectWatch.setOnClickListener(v ->
                startActivity(new Intent(this, WearableManagerActivity.class)));
        }
        if (btnTeachAlerts != null) {
            btnTeachAlerts.setOnClickListener(v ->
                startActivity(new Intent(this, ModelManagerActivity.class)));
        }
    }

    private void applyTopInsetsToTopBar() {
        View topBar = findViewById(R.id.topBarHome);
        if (topBar == null) return;
        final int baseLeft   = topBar.getPaddingLeft();
        final int baseTop    = topBar.getPaddingTop();
        final int baseRight  = topBar.getPaddingRight();
        final int baseBottom = topBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(baseLeft, baseTop + topInset, baseRight, baseBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(topBar);
    }

    private void applyBottomInsetsToContent() {
        View content = findViewById(R.id.homeActions);
        if (content == null) return;
        final int baseLeft   = content.getPaddingLeft();
        final int baseTop    = content.getPaddingTop();
        final int baseRight  = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();
        final int extra = dpToPx(12);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(baseLeft, baseTop, baseRight, baseBottom + bottomInset + extra);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
