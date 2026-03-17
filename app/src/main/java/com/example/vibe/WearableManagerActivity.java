package com.example.vibe;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

public class WearableManagerActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "EdgeImpulsePrefs";
    private static final String PREF_MODE  = "active_mode";
    private static final String MODE_NV    = "nonvoice";
    private static final String MODE_VC    = "voice";
    private static final String PREF_BLE   = "ble_address";
    private static final String PREF_WATCH_CONNECTED = "pref_watch_connected";
    private static final int REQ_BLE_CONNECT = 1003;

    private ImageButton buttonBackTop;

    private MaterialButton btnModeSounds, btnModeVoice;
    private TextView tvModeDescription;

    private View dotConn;
    private TextView tvConnState, tvBleAddr;
    private MaterialButton btnConnect;

    private TextView tvWatchModel;
    private TextView tvWatchClasses;

    private TextView tvStatus;
    private ProgressBar pb;
    private TextView tvPct;

    private MaterialButton btnProceedTeachAlerts;
    private MaterialButton btnSendExistingModel;
    private MaterialButton btnUpdateMyName;

    private String mode;
    private String ble;

    private boolean modeChosenThisSession = false;
    private BluetoothGatt gatt;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wearable_manager);

        bindViews();
        bindActions();

        applyTopInsetsToTopBar();
        applyBottomInsetsToContent();

        loadPrefs();
        applyModeUi();
        refreshConnUi(isConnected());

        showModeGateDialog();
        updateEnabledState();
    }

    @Override protected void onResume() {
        super.onResume();
        loadPrefs();
        applyModeUi();
        refreshConnUi(isConnected());
        updateEnabledState();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        safeClose();
    }

    private void bindViews() {
        buttonBackTop = findViewById(R.id.buttonBackTop);

        btnModeSounds = findViewById(R.id.buttonModeNonVoice);
        btnModeVoice  = findViewById(R.id.buttonModeVoice);
        tvModeDescription = findViewById(R.id.textModeDescription);

        dotConn     = findViewById(R.id.viewConnectionDotWearable);
        tvConnState = findViewById(R.id.textConnectionStateWearable);
        tvBleAddr   = findViewById(R.id.textBleAddressWearable);
        btnConnect  = findViewById(R.id.buttonConnectWearable);

        tvWatchModel   = findViewById(R.id.textWatchModelWearable);
        tvWatchClasses = findViewById(R.id.textWatchClassesWearable);

        tvStatus = findViewById(R.id.textWearableStatus);
        pb       = findViewById(R.id.progressWearable);
        tvPct    = findViewById(R.id.textWearablePercent);

        btnProceedTeachAlerts = findViewById(R.id.buttonProceedTeachAlerts);
        btnSendExistingModel  = findViewById(R.id.buttonSendExistingModelWearable);
        btnUpdateMyName       = findViewById(R.id.buttonUpdateMyNameWearable);

        if (pb != null) {
            pb.setMax(100);
            pb.setProgress(0);
            pb.setVisibility(View.GONE);
        }
        if (tvPct != null) tvPct.setVisibility(View.GONE);
    }

    private void bindActions() {
        if (buttonBackTop != null) buttonBackTop.setOnClickListener(v -> finish());

        if (btnModeSounds != null) btnModeSounds.setOnClickListener(v -> onModeChosen(MODE_NV));
        if (btnModeVoice != null)  btnModeVoice.setOnClickListener(v -> onModeChosen(MODE_VC));

        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                if (!modeChosenThisSession) {
                    showModeGateDialog();
                    return;
                }
                if (isConnected()) {
                    safeClose();
                    refreshConnUi(false);
                    updateEnabledState();
                } else {
                    connectWatch();
                }
            });
        }

        if (btnProceedTeachAlerts != null) {
            btnProceedTeachAlerts.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, "Please connect my watch first.", Toast.LENGTH_LONG).show();
                    return;
                }
                startActivity(new Intent(this, ModelManagerActivity.class));
            });
        }

        if (btnSendExistingModel != null) {
            btnSendExistingModel.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, "Please connect my watch first.", Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "Send existing alerts: connect your existing transfer code here.", Toast.LENGTH_LONG).show();
            });
        }

        if (btnUpdateMyName != null) {
            btnUpdateMyName.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, "Please connect my watch first.", Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, "Update my name: connect your existing update-labels code here.", Toast.LENGTH_LONG).show();
            });
        }
    }

    private void onModeChosen(String newMode) {
        setMode(newMode);
        modeChosenThisSession = true;
        updateEnabledState();
    }

    private void showModeGateDialog() {
        if (modeChosenThisSession) return;

        new AlertDialog.Builder(this)
                .setTitle("What should my watch listen for?")
                .setMessage("Choose one to continue:\n\n• Sounds (horn, doorbell)\n• My name (someone calls me)")
                .setCancelable(false)
                .setPositiveButton("Sounds", (d, w) -> onModeChosen(MODE_NV))
                .setNegativeButton("My name", (d, w) -> onModeChosen(MODE_VC))
                .show();
    }

    private void updateEnabledState() {
        boolean modeOk = modeChosenThisSession;
        boolean connected = isConnected();

        if (btnConnect != null) {
            btnConnect.setEnabled(modeOk);
            btnConnect.setAlpha(modeOk ? 1f : 0.45f);
        }

        setActionEnabled(btnProceedTeachAlerts, connected);
        setActionEnabled(btnSendExistingModel, connected);
        setActionEnabled(btnUpdateMyName, connected);

        if (tvStatus != null) {
            if (!modeOk) tvStatus.setText("Choose what my watch should listen for.");
            else if (!connected) tvStatus.setText("Good. Now connect my watch.");
            else tvStatus.setText("My watch is connected. Choose an action below.");
        }
    }

    private void setActionEnabled(MaterialButton b, boolean enabled) {
        if (b == null) return;
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.45f);
    }

    private boolean isConnected() {
        return gatt != null;
    }

    private void loadPrefs() {
        SharedPreferences sp = prefs();
        mode = sp.getString(PREF_MODE, MODE_NV);
        if (!MODE_VC.equals(mode)) mode = MODE_NV;
        ble = sp.getString(PREF_BLE, "ble_address");
        if (ble == null || ble.trim().isEmpty() || "ble_address".equals(ble)) {
            ble = sp.getString(PREF_BLE, "A0:DD:6C:73:77:76");
        }
        if (tvBleAddr != null) tvBleAddr.setText("Watch address: " + ble);
        if (tvWatchModel != null) tvWatchModel.setText("Alerts on my watch: not sent yet");
        if (tvWatchClasses != null) tvWatchClasses.setText("My name on watch: not set");
    }

    private void setMode(String newMode) {
        prefs().edit().putString(PREF_MODE, newMode).apply();
        loadPrefs();
        applyModeUi();
    }

    private void applyModeUi() {
        boolean v = MODE_VC.equals(mode);
        if (tvModeDescription != null) {
            tvModeDescription.setText(v
                    ? "My name: my watch can vibrate when someone calls my name."
                    : "Sounds: my watch can vibrate for important sounds like a horn or doorbell.");
        }
        if (btnModeSounds != null && btnModeVoice != null) {
            modeBtn(v ? btnModeVoice : btnModeSounds, true);
            modeBtn(v ? btnModeSounds : btnModeVoice, false);
        }
        if (btnModeSounds != null) btnModeSounds.setText("👂 Sounds");
        if (btnModeVoice != null)  btnModeVoice.setText("🗣️ My name");
    }

    private void modeBtn(MaterialButton b, boolean active) {
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor(active ? "#1A237E" : "#F2F4F8")));
        b.setTextColor(android.graphics.Color.parseColor(active ? "#FFFFFF" : "#6B7280"));
    }

    private void refreshConnUi(boolean on) {
        prefs().edit().putBoolean(PREF_WATCH_CONNECTED, on).apply();
        if (tvConnState != null) {
            tvConnState.setText(on ? "✅ Connected" : "❌ Not connected");
            tvConnState.setTextColor(android.graphics.Color.parseColor(on ? "#22C55E" : "#EF4444"));
        }
        if (dotConn != null) dotConn.setBackgroundResource(on ? R.drawable.circle_green : R.drawable.circle_red);
        if (btnConnect != null) btnConnect.setText(on ? "Disconnect" : "Connect");
        if (pb != null) pb.setVisibility(View.GONE);
        if (tvPct != null) tvPct.setVisibility(View.GONE);
    }

    private void connectWatch() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null || !ba.isEnabled()) {
            Toast.makeText(this, "Please turn on Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE_CONNECT);
            return;
        }
        try {
            BluetoothDevice dev = ba.getRemoteDevice(ble);
            safeClose();
            if (pb != null) pb.setVisibility(View.VISIBLE);
            if (tvStatus != null) tvStatus.setText("Connecting to my watch…");
            gatt = dev.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        runOnUiThread(() -> { refreshConnUi(true); updateEnabledState(); });
                    } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        runOnUiThread(() -> { refreshConnUi(false); safeClose(); updateEnabledState(); });
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, "Watch address looks wrong. Please check Settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void safeClose() {
        if (gatt != null) {
            try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                    gatt.disconnect();
            } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_CONNECT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) connectWatch();
            else Toast.makeText(this, "Bluetooth permission is needed to connect my watch.", Toast.LENGTH_LONG).show();
        }
    }

    private void applyTopInsetsToTopBar() {
        View topBar = findViewById(R.id.topBar);
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
        View content = findViewById(R.id.wearableContent);
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
