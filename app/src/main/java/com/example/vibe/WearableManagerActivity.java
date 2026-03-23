package com.example.vibe;

import android.Manifest;
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

    private static final String PREFS_NAME        = "EdgeImpulsePrefs";
    private static final String PREF_MODE         = "active_mode";
    private static final String MODE_NV           = "nonvoice";
    private static final String MODE_VC           = "voice";
    private static final String PREF_BLE          = "ble_address";
    private static final String PREF_WATCH_CONNECTED = "pref_watch_connected";
    private static final int    REQ_BLE_CONNECT   = 1003;

    private ImageButton  buttonBackTop;
    private MaterialButton btnModeSounds, btnModeVoice;
    private TextView     tvModeDescription;

    private View         dotConn;
    private TextView     tvConnState, tvBleAddr;
    private MaterialButton btnConnect;

    private TextView     tvWatchModel;
    private TextView     tvWatchClasses;

    private TextView     tvStatus;
    private ProgressBar  pb;
    private TextView     tvPct;

    private MaterialButton btnProceedTeachAlerts;
    private MaterialButton btnSendExistingModel;
    private MaterialButton btnUpdateMyName;

    private TextView     tvUpdateNameHint;
    private TextView     tvTeachAlertsHint;
    private TextView     tvSendSavedHint;

    private String       mode;
    private String       ble;

    private boolean      modeChosen = false;
    private BluetoothGatt gatt;

    // ─────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wearable_manager);

        bindViews();
        applyActionButtonText();
        bindActions();

        applyTopInsetsToTopBar();
        applyBottomInsetsToContent();

        loadPrefs();
        applyModeUi();
        refreshConnUi(isConnected());

        showModeGateDialog();
        updateEnabledState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPrefs();
        applyModeUi();
        refreshConnUi(isConnected());
        updateEnabledState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        safeClose();
    }

    // ─────────────────────────────────────────────
    //  Bind
    // ─────────────────────────────────────────────

    private void bindViews() {
        buttonBackTop     = findViewById(R.id.buttonBackTop);

        btnModeSounds     = findViewById(R.id.buttonModeNonVoice);
        btnModeVoice      = findViewById(R.id.buttonModeVoice);
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

        tvUpdateNameHint  = findViewById(R.id.textUpdateNameHint);
        tvTeachAlertsHint = findViewById(R.id.textTeachAlertsHint);
        tvSendSavedHint   = findViewById(R.id.textSendSavedHint);

        if (pb != null) {
            pb.setMax(100);
            pb.setProgress(0);
            pb.setVisibility(View.GONE);
        }
        if (tvPct != null) tvPct.setVisibility(View.GONE);
    }

    private void applyActionButtonText() {
        if (btnUpdateMyName != null)
            btnUpdateMyName.setText("Set alert name");
        if (btnProceedTeachAlerts != null)
            btnProceedTeachAlerts.setText("Teach new alerts");
        if (btnSendExistingModel != null)
            btnSendExistingModel.setText("Send alerts to watch");

        if (tvUpdateNameHint != null)
            tvUpdateNameHint.setText("Tell the watch what name to listen for");
        if (tvTeachAlertsHint != null)
            tvTeachAlertsHint.setText("Add sounds or your name for the watch to learn");
        if (tvSendSavedHint != null)
            tvSendSavedHint.setText("Push saved alerts from phone to watch");
    }

    private void bindActions() {
        if (buttonBackTop != null)
            buttonBackTop.setOnClickListener(v -> finish());

        if (btnModeSounds != null)
            btnModeSounds.setOnClickListener(v -> onModeChosen(MODE_NV));
        if (btnModeVoice != null)
            btnModeVoice.setOnClickListener(v -> onModeChosen(MODE_VC));

        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                if (!modeChosen) { showModeGateDialog(); return; }
                if (isConnected()) {
                    safeClose();
                    refreshConnUi(false);
                    updateEnabledState();
                } else {
                    connectWatch();
                }
            });
        }

        if (btnUpdateMyName != null) {
            btnUpdateMyName.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this,
                            "Please connect your watch first.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // TODO: open name-update flow
                Toast.makeText(this,
                        "Set the name the watch should listen for.",
                        Toast.LENGTH_LONG).show();
            });
        }

        if (btnProceedTeachAlerts != null) {
            btnProceedTeachAlerts.setOnClickListener(v ->
                    startActivity(new Intent(this, ModelManagerActivity.class)));
        }

        if (btnSendExistingModel != null) {
            btnSendExistingModel.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this,
                            "Please connect your watch first.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                // TODO: trigger send flow
                Toast.makeText(this,
                        "Sending saved alerts to your watch...",
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    // ─────────────────────────────────────────────
    //  Mode gate dialog — custom card style
    // ─────────────────────────────────────────────

    private void showModeGateDialog() {
        if (modeChosen) return;

        // Inflate our custom layout
        View dialogView = getLayoutInflater()
                .inflate(R.layout.dialog_choose_mode, null);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        // transparent window so our rounded card shows cleanly
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(
                    android.R.color.transparent);

        View           optionSounds  = dialogView.findViewById(R.id.dialogOptionSounds);
        View           optionMyName  = dialogView.findViewById(R.id.dialogOptionMyName);
        TextView       checkSounds   = dialogView.findViewById(R.id.dialogCheckSounds);
        TextView       checkMyName   = dialogView.findViewById(R.id.dialogCheckMyName);
        MaterialButton btnContinue   = dialogView.findViewById(R.id.dialogBtnContinue);

        // track selection inside the dialog
        final String[] chosen = { MODE_NV };

        // default highlight based on saved mode
        if (MODE_VC.equals(mode)) {
            chosen[0] = MODE_VC;
            checkMyName.setVisibility(View.VISIBLE);
            checkSounds.setVisibility(View.INVISIBLE);
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
        } else {
            checkSounds.setVisibility(View.VISIBLE);
            checkMyName.setVisibility(View.INVISIBLE);
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
        }

        optionSounds.setOnClickListener(v -> {
            chosen[0] = MODE_NV;
            checkSounds.setVisibility(View.VISIBLE);
            checkMyName.setVisibility(View.INVISIBLE);
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
        });

        optionMyName.setOnClickListener(v -> {
            chosen[0] = MODE_VC;
            checkMyName.setVisibility(View.VISIBLE);
            checkSounds.setVisibility(View.INVISIBLE);
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
        });

        btnContinue.setOnClickListener(v -> {
            onModeChosen(chosen[0]);
            dialog.dismiss();
        });

        dialog.show();
    }

    // ─────────────────────────────────────────────
    //  Mode helpers
    // ─────────────────────────────────────────────

    private void onModeChosen(String newMode) {
        setMode(newMode);
        modeChosen = true;
        updateEnabledState();
    }

    private void updateEnabledState() {
        boolean modeOk    = modeChosen;
        boolean connected = isConnected();

        if (btnConnect != null) {
            btnConnect.setEnabled(modeOk);
            btnConnect.setAlpha(modeOk ? 1f : 0.45f);
        }

        // Action buttons are always enabled; their click handlers show a
        // "please connect first" toast when the watch is not yet connected.
        setActionEnabled(btnUpdateMyName,       true);
        setActionEnabled(btnProceedTeachAlerts, true);
        setActionEnabled(btnSendExistingModel,  true);

        if (tvStatus != null) {
            if (!modeOk)      tvStatus.setText("Pick Sounds or My name to continue.");
            else if (!connected) tvStatus.setText("Now connect your watch.");
            else              tvStatus.setText("Watch connected. Choose what to do.");
        }
    }

    private void setActionEnabled(MaterialButton b, boolean enabled) {
        if (b == null) return;
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.45f);
    }

    // ─────────────────────────────────────────────
    //  Prefs / mode
    // ─────────────────────────────────────────────

    private void loadPrefs() {
        SharedPreferences sp = prefs();

        mode = sp.getString(PREF_MODE, MODE_NV);
        if (!MODE_VC.equals(mode)) mode = MODE_NV;

        // If a mode was already persisted, treat it as chosen so the Connect
        // button is enabled immediately on return visits.
        if (sp.contains(PREF_MODE)) modeChosen = true;

        ble = sp.getString(PREF_BLE, "");
        if (ble == null || ble.trim().isEmpty() || "ble_address".equals(ble))
            ble = "A0:DD:6C:73:77:76";

        if (tvBleAddr   != null) tvBleAddr.setText("Watch address: " + ble);
        if (tvWatchModel   != null) tvWatchModel.setText("Not sent yet");
        if (tvWatchClasses != null) tvWatchClasses.setText("Not set");
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
                    ? "Vibrates when someone calls you"
                    : "Vibrates for sounds like horn or doorbell.");
        }

        if (btnModeSounds != null && btnModeVoice != null) {
            modeBtn(v ? btnModeVoice  : btnModeSounds, true);
            modeBtn(v ? btnModeSounds : btnModeVoice,  false);
        }

        if (btnModeSounds != null) btnModeSounds.setText("Sounds");
        if (btnModeVoice  != null) btnModeVoice.setText("My name");
    }

    private void modeBtn(MaterialButton b, boolean active) {
        b.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(
                                active ? "#1A237E" : "#EEF2FF")));
        b.setTextColor(android.graphics.Color.parseColor(
                active ? "#FFFFFF" : "#6B7280"));
    }

    // ─────────────────────────────────────────────
    //  BLE
    // ─────────────────────────────────────────────

    private boolean isConnected() { return gatt != null; }

    private void refreshConnUi(boolean on) {
        prefs().edit().putBoolean(PREF_WATCH_CONNECTED, on).apply();

        if (tvConnState != null) {
            tvConnState.setText(on ? "Connected" : "Not connected");
            tvConnState.setTextColor(android.graphics.Color.parseColor(
                    on ? "#22C55E" : "#EF4444"));
        }
        if (dotConn != null)
            dotConn.setBackgroundResource(
                    on ? R.drawable.circle_green : R.drawable.circle_red);
        if (btnConnect != null)
            btnConnect.setText(on ? "Disconnect" : "Connect");
        if (pb  != null) pb.setVisibility(View.GONE);
        if (tvPct != null) tvPct.setVisibility(View.GONE);
    }

    private void connectWatch() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null || !ba.isEnabled()) {
            Toast.makeText(this, "Please turn on Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.BLUETOOTH_CONNECT },
                    REQ_BLE_CONNECT);
            return;
        }
        try {
            BluetoothDevice dev = ba.getRemoteDevice(ble);
            safeClose();
            if (pb != null) pb.setVisibility(View.VISIBLE);
            if (tvStatus != null) tvStatus.setText("Connecting...");

            gatt = dev.connectGatt(this, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        runOnUiThread(() -> {
                            refreshConnUi(true);
                            updateEnabledState();
                        });
                    } else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        runOnUiThread(() -> {
                            refreshConnUi(false);
                            safeClose();
                            updateEnabledState();
                        });
                    }
                }
            });
        } catch (IllegalArgumentException e) {
            Toast.makeText(this,
                    "Watch address looks wrong. Check Settings.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void safeClose() {
        if (gatt == null) return;
        try {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED)
                gatt.disconnect();
        } catch (Exception ignored) {}
        try { gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }

    // ─────────────────────────────────────────────
    //  Permissions
    // ─────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_CONNECT) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                connectWatch();
            else
                Toast.makeText(this,
                        "Bluetooth permission is needed to connect your watch.",
                        Toast.LENGTH_LONG).show();
        }
    }

    // ─────────────────────────────────────────────
    //  Insets
    // ─────────────────────────────────────────────

    private void applyTopInsetsToTopBar() {
        View topBar = findViewById(R.id.topBar);
        if (topBar == null) return;
        final int bL = topBar.getPaddingLeft(), bT = topBar.getPaddingTop(),
                bR = topBar.getPaddingRight(), bB = topBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(bL, bT + top, bR, bB);
            return insets;
        });
        ViewCompat.requestApplyInsets(topBar);
    }

    private void applyBottomInsetsToContent() {
        View content = findViewById(R.id.wearableContent);
        if (content == null) return;
        final int bL = content.getPaddingLeft(), bT = content.getPaddingTop(),
                bR = content.getPaddingRight(), bB = content.getPaddingBottom();
        final int extra = dpToPx(12);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(bL, bT, bR, bB + bottom + extra);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }
}
