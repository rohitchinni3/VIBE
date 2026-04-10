package com.example.vibe;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

public class HelpActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private View[] dots;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        applyTopInsetsToTopBar();
        applyBottomInsetsToBottomBar();

        viewPager = findViewById(R.id.viewPagerHelp);
        MaterialButton btnBack = findViewById(R.id.buttonBackToHomeHelp);

        if (viewPager != null) {
            viewPager.setAdapter(new HelpSlideAdapter(this));
            viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override public void onPageSelected(int position) {
                    updateDots(position);
                }
            });
        }

        dots = new View[]{
            findViewById(R.id.dotHelp0),
            findViewById(R.id.dotHelp1),
            findViewById(R.id.dotHelp2),
            findViewById(R.id.dotHelp3)
        };
        updateDots(0);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
    }

    private void updateDots(int active) {
        if (dots == null) return;
        for (int i = 0; i < dots.length; i++) {
            if (dots[i] == null) continue;
            dots[i].setBackgroundResource(i == active ? R.drawable.circle_green : R.drawable.circle_red);
        }
    }

    private void applyTopInsetsToTopBar() {
        View topBar = findViewById(R.id.topBarHelp);
        if (topBar == null) return;
        final int bl = topBar.getPaddingLeft(), bt = topBar.getPaddingTop(),
                  br = topBar.getPaddingRight(), bb = topBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            v.setPadding(bl, bt + insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, br, bb);
            return insets;
        });
        ViewCompat.requestApplyInsets(topBar);
    }

    private void applyBottomInsetsToBottomBar() {
        View bottomBar = findViewById(R.id.bottomBarHelp);
        if (bottomBar == null) return;
        final int bl = bottomBar.getPaddingLeft(), bt = bottomBar.getPaddingTop(),
                  br = bottomBar.getPaddingRight(), bb = bottomBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v, insets) -> {
            int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(bl, bt, br, bb + bottomInset);
            return insets;
        });
        ViewCompat.requestApplyInsets(bottomBar);
    }
}
