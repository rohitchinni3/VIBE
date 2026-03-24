package com.example.vibe;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class WearableManagerActivity extends AppCompatActivity {

    private static final String TAG = "WearableMgr";

    private static final String PREFS_NAME           = "EdgeImpulsePrefs";
    private static final String PREF_MODE            = "active_mode";
    private static final String MODE_NV              = "nonvoice";
    private static final String MODE_VC              = "voice";
    private static final String PREF_BLE             = "ble_address";
    private static final String PREF_WATCH_CONNECTED = "pref_watch_connected";
    private static final int    REQ_BLE_CONNECT      = 1003;

    // Pref keys for label and transfer history
    private static final String PREF_LBL_NV           = "labels_set_nonvoice";
    private static final String PREF_LBL_VC           = "labels_set_voice";
    private static final String PREF_XFER_NV          = "last_transferred_nonvoice";
    private static final String PREF_XFER_VC          = "last_transferred_voice";
    private static final String PREF_LAST_LBL_SENT_NV = "last_labels_sent_nonvoice";
    private static final String PREF_LAST_LBL_SENT_VC = "last_labels_sent_voice";

    // BLE transfer constants
    private static final byte[] END_MARKER              = "END".getBytes();
    private static final int    MAX_RETRIES             = 3;
    private static final int    ACK_TIMEOUT_MS          = 2000;
    private static final int    BLE_WRITE_DELAY_MS      = 300;
    private static final int    BLE_CCCD_DELAY_MS       = 200;
    private static final int    DISCONNECT_DELAY_MS     = 800;
    private static final int    MAX_LABEL_DISPLAY_LENGTH = 80;

    // BLE UUIDs
    private static final UUID NV_OTA_SVC = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b");
    private static final UUID NV_OTA_CHR = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8");
    private static final UUID NV_CLS_SVC = UUID.fromString("d6736a2b-1e3c-4a1b-97e6-6a2a8d8c447a");
    private static final UUID NV_CLS_CHR = UUID.fromString("7acb2cff-5b5d-4f2d-bf34-cdb39f5e374a");
    private static final UUID VC_SVC     = UUID.fromString("d1f6e001-1111-4444-8888-0a0b0c0d0e0f");
    private static final UUID VC_MDL_CHR = UUID.fromString("e2f6a002-2222-5555-9999-1a1b1c1d1e1f");
    private static final UUID VC_CLS_CHR = UUID.fromString("f3f6c003-3333-6666-aaaa-2a2b2c2d2e2f");
    private static final UUID TGT_SVC    = UUID.fromString("a92c6d12-6c20-4d31-a5a2-6f4e6c0e0001");
    private static final UUID TGT_CHR    = UUID.fromString("a92c6d12-6c20-4d31-a5a2-6f4e6c0e0002");
    private static final UUID CCCD       = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Views
    private ImageButton    buttonBackTop;
    private MaterialButton btnModeSounds, btnModeVoice;
    private TextView       tvModeDescription;

    private View           dotConn;
    private TextView       tvConnState, tvBleAddr;
    private MaterialButton btnConnect;

    private TextView       tvWatchModel;
    private TextView       tvWatchClasses;

    private TextView       tvStatus;
    private ProgressBar    pb;
    private TextView       tvPct;

    private MaterialButton btnProceedTeachAlerts;
    private MaterialButton btnSendExistingModel;
    private MaterialButton btnUpdateMyName;

    private TextView       tvUpdateNameHint;
    private TextView       tvTeachAlertsHint;
    private TextView       tvSendSavedHint;

    // App state
    private String        mode;
    private String        ble;
    private boolean       modeChosenThisSession = false;
    private BluetoothGatt gatt;

    // BLE transfer state
    private UUID otaSvc, otaChr, clsSvc, clsChr;
    private BluetoothGattCharacteristic modelChr, classChr, targetChr;
    private byte[][] chunks;
    private int      chunkIdx = 0, retries = 0;
    private boolean  waitAck  = false;
    private final Handler ackH = new Handler(Looper.getMainLooper());
    private boolean  pendingTarget = false;
    private String   pendingName   = null;
    private String   pendingCsv    = null;
    private File     selectedModelDir = null;

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
        ackH.removeCallbacksAndMessages(null);
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

        tvUpdateNameHint  = findViewById(R.id.textHintUpdateName);
        tvTeachAlertsHint = findViewById(R.id.textHintTeachAlerts);
        tvSendSavedHint   = findViewById(R.id.textHintSendModel);

        if (pb != null) {
            pb.setMax(100);
            pb.setProgress(0);
            pb.setVisibility(View.GONE);
        }
        if (tvPct != null) tvPct.setVisibility(View.GONE);
    }

    private void applyActionButtonText() {
        if (btnUpdateMyName != null)
            btnUpdateMyName.setText("Update watch alert name");
        if (btnProceedTeachAlerts != null)
            btnProceedTeachAlerts.setText("Teach new alerts");
        if (btnSendExistingModel != null)
            btnSendExistingModel.setText("Send saved alerts to watch");

        if (tvUpdateNameHint != null)
            tvUpdateNameHint.setText("Set the name used for 'My name' alerts.");
        if (tvTeachAlertsHint != null)
            tvTeachAlertsHint.setText("Add new sounds (or my name) and improve alerts.");
        if (tvSendSavedHint != null)
            tvSendSavedHint.setText("Send alerts already saved on this phone to the watch.");
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
                if (!modeChosenThisSession) { showModeGateDialog(); return; }
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
                    Toast.makeText(this, "Please connect your watch first.", Toast.LENGTH_LONG).show();
                    return;
                }
                confirmThenUpdateLabelsFlow();
            });
        }

        if (btnProceedTeachAlerts != null) {
            btnProceedTeachAlerts.setOnClickListener(v ->
                    startActivity(new Intent(this, ModelManagerActivity.class)));
        }

        if (btnSendExistingModel != null) {
            btnSendExistingModel.setOnClickListener(v -> {
                if (!isConnected()) {
                    Toast.makeText(this, "Please connect your watch first.", Toast.LENGTH_LONG).show();
                    return;
                }
                chooseModelThenSend();
            });
        }
    }

    // ─────────────────────────────────────────────
    //  Mode gate dialog — custom card style
    // ─────────────────────────────────────────────

    private void showModeGateDialog() {
        if (modeChosenThisSession) return;

        View dialogView = getLayoutInflater()
                .inflate(R.layout.dialog_choose_mode, null);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        View           optionSounds    = dialogView.findViewById(R.id.dialogOptionSounds);
        View           optionMyName    = dialogView.findViewById(R.id.dialogOptionMyName);
        TextView       checkSounds     = dialogView.findViewById(R.id.dialogCheckSounds);
        TextView       checkMyName     = dialogView.findViewById(R.id.dialogCheckMyName);
        TextView       titleSounds     = dialogView.findViewById(R.id.dialogTitleSounds);
        TextView       subtitleSounds  = dialogView.findViewById(R.id.dialogSubtitleSounds);
        TextView       titleMyName     = dialogView.findViewById(R.id.dialogTitleMyName);
        TextView       subtitleMyName  = dialogView.findViewById(R.id.dialogSubtitleMyName);
        MaterialButton btnContinue     = dialogView.findViewById(R.id.dialogBtnContinue);

        final String[] chosen = { MODE_NV };

        if (MODE_VC.equals(mode)) {
            chosen[0] = MODE_VC;
            checkMyName.setVisibility(View.VISIBLE);
            checkSounds.setVisibility(View.INVISIBLE);
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
            setModeOptionColors(titleMyName, subtitleMyName, checkMyName, true);
            setModeOptionColors(titleSounds, subtitleSounds, checkSounds, false);
        } else {
            checkSounds.setVisibility(View.VISIBLE);
            checkMyName.setVisibility(View.INVISIBLE);
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
            setModeOptionColors(titleSounds, subtitleSounds, checkSounds, true);
            setModeOptionColors(titleMyName, subtitleMyName, checkMyName, false);
        }

        optionSounds.setOnClickListener(v -> {
            chosen[0] = MODE_NV;
            checkSounds.setVisibility(View.VISIBLE);
            checkMyName.setVisibility(View.INVISIBLE);
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
            setModeOptionColors(titleSounds, subtitleSounds, checkSounds, true);
            setModeOptionColors(titleMyName, subtitleMyName, checkMyName, false);
        });

        optionMyName.setOnClickListener(v -> {
            chosen[0] = MODE_VC;
            checkMyName.setVisibility(View.VISIBLE);
            checkSounds.setVisibility(View.INVISIBLE);
            optionMyName.setBackground(getDrawable(R.drawable.bg_mode_option_selected));
            optionSounds.setBackground(getDrawable(R.drawable.bg_mode_option_unselected));
            setModeOptionColors(titleMyName, subtitleMyName, checkMyName, true);
            setModeOptionColors(titleSounds, subtitleSounds, checkSounds, false);
        });

        btnContinue.setOnClickListener(v -> {
            onModeChosen(chosen[0]);
            dialog.dismiss();
        });

        // Allow back-press to leave the screen instead of blocking indefinitely.
        dialog.setOnCancelListener(d -> finish());

        dialog.show();
    }

    // ─────────────────────────────────────────────
    //  Mode helpers
    // ─────────────────────────────────────────────

    /** Updates text colors for a mode option row to match the selected/unselected state. */
    private void setModeOptionColors(TextView title, TextView subtitle, TextView check,
                                     boolean selected) {
        int color = selected ? android.graphics.Color.WHITE
                             : android.graphics.Color.parseColor("#1A237E");
        title.setTextColor(color);
        subtitle.setTextColor(color);
        check.setTextColor(android.graphics.Color.WHITE);
    }

    private void onModeChosen(String newMode) {
        setMode(newMode);
        modeChosenThisSession = true;
        updateEnabledState();
    }

    private void updateEnabledState() {
        boolean modeOk    = modeChosenThisSession;
        boolean connected = isConnected();

        if (btnConnect != null) {
            btnConnect.setEnabled(modeOk);
            btnConnect.setAlpha(modeOk ? 1f : 0.45f);
        }

        setActionEnabled(btnUpdateMyName,       connected);
        setActionEnabled(btnProceedTeachAlerts, connected);
        setActionEnabled(btnSendExistingModel,  connected);

        if (tvStatus != null) {
            if (!modeOk)         tvStatus.setText("Choose what my watch should listen for.");
            else if (!connected) tvStatus.setText("Good. Now connect my watch.");
            else                 tvStatus.setText("My watch is connected. Choose an action below.");
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
        if (sp.contains(PREF_MODE)) modeChosenThisSession = true;

        ble = sp.getString(PREF_BLE, "");
        if (ble == null || ble.trim().isEmpty() || "ble_address".equals(ble))
            ble = "A0:DD:6C:73:77:76";

        if (tvBleAddr != null) tvBleAddr.setText("Watch address: " + ble);

        rebuildMode();

        String lastModel = sp.getString(MODE_VC.equals(mode) ? PREF_XFER_VC : PREF_XFER_NV, "");
        String lastLbl   = sp.getString(MODE_VC.equals(mode) ? PREF_LAST_LBL_SENT_VC : PREF_LAST_LBL_SENT_NV, "");
        if (tvWatchModel != null)
            tvWatchModel.setText(TextUtils.isEmpty(lastModel) ? "Alerts on my watch: not sent yet" : "Last sent: " + lastModel);
        if (tvWatchClasses != null)
            tvWatchClasses.setText(TextUtils.isEmpty(lastLbl) ? "My name on watch: not set" : "Last: " + lastLbl);
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
            modeBtn(v ? btnModeVoice  : btnModeSounds, true);
            modeBtn(v ? btnModeSounds : btnModeVoice,  false);
        }

        if (btnModeSounds != null) btnModeSounds.setText("👂 Sounds");
        if (btnModeVoice  != null) btnModeVoice.setText("🗣️ My name");
    }

    private void modeBtn(MaterialButton b, boolean active) {
        b.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(
                                active ? "#1A237E" : "#EEF2FF")));
        b.setTextColor(android.graphics.Color.parseColor(
                active ? "#FFFFFF" : "#6B7280"));
    }

    /** Sets otaSvc/otaChr/clsSvc/clsChr based on the current mode. */
    private void rebuildMode() {
        if (MODE_VC.equals(mode)) {
            otaSvc = VC_SVC;
            otaChr = VC_MDL_CHR;
            clsSvc = VC_SVC;
            clsChr = VC_CLS_CHR;
        } else {
            otaSvc = NV_OTA_SVC;
            otaChr = NV_OTA_CHR;
            clsSvc = NV_CLS_SVC;
            clsChr = NV_CLS_CHR;
        }
    }

    // ─────────────────────────────────────────────
    //  Model selection and sending
    // ─────────────────────────────────────────────

    private void chooseModelThenSend() {
        List<File> dirs = listSavedModelDirs();
        if (dirs.isEmpty()) {
            Toast.makeText(this,
                    "No saved models found. Please teach alerts first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        View         dialogView = getLayoutInflater().inflate(R.layout.dialog_choose_model, null);
        LinearLayout container  = dialogView.findViewById(R.id.dialogModelItemsContainer);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        int rowSpacingPx = dpToPx(10);
        for (File dir : dirs) {
            View item = getLayoutInflater().inflate(R.layout.item_model_choice, container, false);
            ((TextView) item.findViewById(R.id.itemModelName)).setText(dir.getName());
            ((TextView) item.findViewById(R.id.itemModelDate))
                    .setText(DateFormat.getDateTimeInstance()
                            .format(new Date(dir.lastModified())));
            item.setOnClickListener(v -> {
                selectedModelDir = dir;
                dialog.dismiss();
                confirmThenSendSelectedModel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, rowSpacingPx);
            item.setLayoutParams(lp);
            container.addView(item);
        }

        dialogView.findViewById(R.id.dialogBtnCancelModel)
                .setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void confirmThenSendSelectedModel() {
        if (selectedModelDir == null) return;
        File   f    = new File(selectedModelDir, "classifier_model_int8.tflite");
        String name = selectedModelDir.getName();
        String size = humanBytes(f.length());
        String date = DateFormat.getDateTimeInstance()
                .format(new Date(selectedModelDir.lastModified()));

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_send, null);
        ((TextView) dialogView.findViewById(R.id.dialogConfirmModelName)).setText(name);
        ((TextView) dialogView.findViewById(R.id.dialogConfirmModelSize)).setText(size);
        ((TextView) dialogView.findViewById(R.id.dialogConfirmModelDate)).setText(date);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        dialogView.findViewById(R.id.dialogBtnCancelSend)
                .setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.dialogBtnConfirmSend)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    sendModelDirToWatch(selectedModelDir);
                });
        dialog.show();
    }

    private List<File> listSavedModelDirs() {
        File base = getExternalFilesDir(null);
        if (base == null) return Collections.emptyList();
        File sub = new File(base, MODE_VC.equals(mode) ? "voice" : "nonvoice");
        if (!sub.isDirectory()) return Collections.emptyList();
        File[] dirs = sub.listFiles();
        if (dirs == null) return Collections.emptyList();
        List<File> result = new ArrayList<>();
        for (File d : dirs) {
            if (d.isDirectory() && new File(d, "classifier_model_int8.tflite").exists())
                result.add(d);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return result;
    }

    private void sendModelDirToWatch(File dir) {
        new Thread(() -> {
            try {
                byte[] weights = loadInt8(dir);
                bleTransfer(weights, 512, dir.getName());
            } catch (IOException e) {
                Log.e(TAG, "loadInt8 failed", e);
                runOnUiThread(() ->
                        Toast.makeText(this,
                                "Failed to load model: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private byte[] loadInt8(File dir) throws IOException {
        File f = new File(dir, "classifier_model_int8.tflite");
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = fis.read(data);
            if (read != data.length)
                throw new IOException("Incomplete read: " + read + "/" + data.length);
        }
        return data;
    }

    // ─────────────────────────────────────────────
    //  Labels update flow
    // ─────────────────────────────────────────────

    private void confirmThenUpdateLabelsFlow() {
        SharedPreferences sp  = prefs();
        String key            = MODE_VC.equals(mode) ? PREF_LBL_VC : PREF_LBL_NV;
        Set<String> labels    = sp.getStringSet(key, new HashSet<>());
        if (labels.isEmpty()) {
            Toast.makeText(this,
                    "No labels found. Please teach alerts first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        List<String> sorted = new ArrayList<>(labels);
        Collections.sort(sorted);
        String csv = TextUtils.join(",", sorted);

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_confirm_labels, null);
        ((TextView) dialogView.findViewById(R.id.dialogLabelsList))
                .setText(TextUtils.join("\n", sorted));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        dialogView.findViewById(R.id.dialogBtnCancelLabels)
                .setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.dialogBtnSendLabels)
                .setOnClickListener(v -> {
                    dialog.dismiss();
                    updateLabelsFlowInternal(csv, sorted);
                });
        dialog.show();
    }

    private void updateLabelsFlowInternal(String csv, List<String> sortedLabels) {
        saveLastLabelsSent(csv);
        if (MODE_VC.equals(mode)) {
            promptTargetThenSend(csv, sortedLabels);
        } else {
            new Thread(() -> bleSendLabels(csv)).start();
        }
    }

    private void promptTargetThenSend(String csv, List<String> cleanedLabels) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_target_name, null);
        TextView tvSubtitle = dialogView.findViewById(R.id.dialogTargetSubtitle);
        EditText etName     = dialogView.findViewById(R.id.dialogTargetNameInput);
        tvSubtitle.setText("Must match one of: " + TextUtils.join(", ", cleanedLabels));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        dialogView.findViewById(R.id.dialogBtnCancelTarget)
                .setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.dialogBtnSendTarget)
                .setOnClickListener(v -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty() || !cleanedLabels.contains(name)) {
                        Toast.makeText(this,
                                "Name must match one of the classes.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    pendingName   = name;
                    pendingTarget = true;
                    pendingCsv    = csv;
                    new Thread(() -> bleSendLabels(csv)).start();
                });
        dialog.show();
    }

    // ─────────────────────────────────────────────
    //  BLE — labels send
    // ─────────────────────────────────────────────

    // Uses BluetoothGattCharacteristic.setValue() and BluetoothGattDescriptor.setValue()
    // which are deprecated in API 33+; the replacement API (BluetoothGatt.writeCharacteristic
    // with a byte[] overload) is not available on older devices we still support.
    @SuppressWarnings("deprecation")
    private void bleSendLabels(String csv) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        if (gatt == null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Not connected to watch.", Toast.LENGTH_LONG).show());
            return;
        }

        BluetoothGattService clsService = gatt.getService(clsSvc);
        if (clsService == null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Watch service not found.", Toast.LENGTH_LONG).show());
            return;
        }
        classChr = clsService.getCharacteristic(clsChr);
        if (classChr == null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Watch characteristic not found.", Toast.LENGTH_LONG).show());
            return;
        }

        // Enable notifications via CCCD
        gatt.setCharacteristicNotification(classChr, true);
        BluetoothGattDescriptor cccd = classChr.getDescriptor(CCCD);
        if (cccd != null) {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
            try { Thread.sleep(BLE_CCCD_DELAY_MS); } catch (InterruptedException ignored) {}
        }

        // Write class CSV
        classChr.setValue(csv.getBytes());
        classChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!gatt.writeCharacteristic(classChr)) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Failed to write labels.", Toast.LENGTH_LONG).show());
            return;
        }
        try { Thread.sleep(BLE_WRITE_DELAY_MS); } catch (InterruptedException ignored) {}

        // Optionally write target characteristic for voice mode
        if (pendingTarget && pendingName != null) {
            BluetoothGattService tgtService = gatt.getService(TGT_SVC);
            if (tgtService != null) {
                targetChr = tgtService.getCharacteristic(TGT_CHR);
                if (targetChr != null) {
                    targetChr.setValue(pendingName.getBytes());
                    targetChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    gatt.writeCharacteristic(targetChr);
                    try { Thread.sleep(BLE_WRITE_DELAY_MS); } catch (InterruptedException ignored) {}
                }
            }
            pendingTarget = false;
            pendingName   = null;
            pendingCsv    = null;
        }

        runOnUiThread(() -> {
            Toast.makeText(this, "Labels sent to watch.", Toast.LENGTH_SHORT).show();
            String lastLbl = prefs().getString(
                    MODE_VC.equals(mode) ? PREF_LAST_LBL_SENT_VC : PREF_LAST_LBL_SENT_NV, "");
            if (tvWatchClasses != null)
                tvWatchClasses.setText(TextUtils.isEmpty(lastLbl) ? "My name on watch: not set" : "Last: " + lastLbl);
            if (tvStatus != null)
                tvStatus.setText("My watch is connected. Choose an action below.");
        });
        disconnLater();
    }

    // ─────────────────────────────────────────────
    //  BLE — OTA model transfer
    // ─────────────────────────────────────────────

    // Uses BluetoothGattCharacteristic.setValue() / gatt.writeCharacteristic(chr) which
    // are deprecated in API 33+. Kept for broad device support.
    @SuppressWarnings("deprecation")
    private void bleTransfer(byte[] weights, int chunkSz, String folderName) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        if (gatt == null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "Not connected to watch.", Toast.LENGTH_LONG).show());
            return;
        }

        BluetoothGattService svc = gatt.getService(otaSvc);
        if (svc == null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "OTA service not found.", Toast.LENGTH_LONG).show());
            return;
        }
        modelChr = svc.getCharacteristic(otaChr);
        if (modelChr == null) {
            runOnUiThread(() ->
                    Toast.makeText(this, "OTA characteristic not found.", Toast.LENGTH_LONG).show());
            return;
        }

        chunks   = split(weights, chunkSz);
        chunkIdx = 0;
        retries  = 0;
        waitAck  = false;
        final int total = chunks.length;

        runOnUiThread(() -> {
            if (pb != null) {
                pb.setMax(total);
                pb.setProgress(0);
                pb.setIndeterminate(false);
                pb.setVisibility(View.VISIBLE);
            }
            if (tvPct != null) { tvPct.setText("0%"); tvPct.setVisibility(View.VISIBLE); }
            if (tvStatus != null) tvStatus.setText("Sending model\u2026");
        });
        sendChunk();
    }

    private byte[][] split(byte[] data, int sz) {
        int count   = (data.length + sz - 1) / sz;
        byte[][] result = new byte[count][];
        for (int i = 0; i < count; i++) {
            int start = i * sz;
            int len   = Math.min(sz, data.length - start);
            result[i] = new byte[len];
            System.arraycopy(data, start, result[i], 0, len);
        }
        return result;
    }

    // Uses BluetoothGattCharacteristic.setValue() / gatt.writeCharacteristic(chr) which
    // are deprecated in API 33+. Kept for broad device support.
    @SuppressWarnings("deprecation")
    private void sendChunk() {
        if (chunks == null || chunkIdx >= chunks.length) {
            sendEnd();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        if (gatt == null || modelChr == null) return;

        modelChr.setValue(chunks[chunkIdx]);
        modelChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        boolean ok = gatt.writeCharacteristic(modelChr);
        if (!ok) {
            retryChunk();
            return;
        }
        waitAck = true;
        ackH.postDelayed(this::retryChunk, ACK_TIMEOUT_MS);
    }

    // Uses BluetoothGattCharacteristic.setValue() / gatt.writeCharacteristic(chr) which
    // are deprecated in API 33+. Kept for broad device support.
    @SuppressWarnings("deprecation")
    private void sendEnd() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) return;
        if (gatt == null || modelChr == null) return;

        modelChr.setValue(END_MARKER);
        modelChr.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        gatt.writeCharacteristic(modelChr);

        if (selectedModelDir != null) saveXfer(selectedModelDir.getName());

        runOnUiThread(() -> {
            if (pb   != null) pb.setVisibility(View.GONE);
            if (tvPct != null) tvPct.setVisibility(View.GONE);
            if (tvStatus != null) tvStatus.setText("My watch is connected. Choose an action below.");
            String lastModel = prefs().getString(
                    MODE_VC.equals(mode) ? PREF_XFER_VC : PREF_XFER_NV, "");
            if (tvWatchModel != null)
                tvWatchModel.setText(TextUtils.isEmpty(lastModel)
                        ? "Alerts on my watch: not sent yet" : "Last sent: " + lastModel);
            Toast.makeText(this, "Model sent to watch.", Toast.LENGTH_SHORT).show();
        });
        disconnLater();
    }

    private void retryChunk() {
        if (!waitAck) return;
        waitAck = false;
        if (++retries > MAX_RETRIES) {
            runOnUiThread(() -> {
                if (pb   != null) pb.setVisibility(View.GONE);
                if (tvPct != null) tvPct.setVisibility(View.GONE);
                if (tvStatus != null) tvStatus.setText("Transfer failed.");
                Toast.makeText(this,
                        "Transfer failed after " + MAX_RETRIES + " retries.",
                        Toast.LENGTH_LONG).show();
            });
            return;
        }
        sendChunk();
    }

    // ─────────────────────────────────────────────
    //  Pref helpers for transfer history
    // ─────────────────────────────────────────────

    private void saveXfer(String modelFolderName) {
        String key = MODE_VC.equals(mode) ? PREF_XFER_VC : PREF_XFER_NV;
        prefs().edit().putString(key, modelFolderName).apply();
    }

    private void saveLastLabelsSent(String csv) {
        String key      = MODE_VC.equals(mode) ? PREF_LAST_LBL_SENT_VC : PREF_LAST_LBL_SENT_NV;
        String truncated = csv.length() > MAX_LABEL_DISPLAY_LENGTH
                ? csv.substring(0, MAX_LABEL_DISPLAY_LENGTH) + "…" : csv;
        prefs().edit().putString(key, truncated).apply();
    }

    // ─────────────────────────────────────────────
    //  BLE connection
    // ─────────────────────────────────────────────

    private boolean isConnected() { return gatt != null; }

    private void refreshConnUi(boolean on) {
        prefs().edit().putBoolean(PREF_WATCH_CONNECTED, on).apply();

        if (tvConnState != null) {
            tvConnState.setText(on ? "✅ Connected" : "❌ Not connected");
            tvConnState.setTextColor(android.graphics.Color.parseColor(
                    on ? "#22C55E" : "#EF4444"));
        }
        if (dotConn != null)
            dotConn.setBackgroundResource(
                    on ? R.drawable.circle_green : R.drawable.circle_red);
        if (btnConnect != null)
            btnConnect.setText(on ? "Disconnect" : "Connect");
        if (pb   != null) pb.setVisibility(View.GONE);
        if (tvPct != null) tvPct.setVisibility(View.GONE);
    }

    @SuppressWarnings("deprecation")
    private void connectWatch() {
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        if (ba == null || !ba.isEnabled()) {
            Toast.makeText(this, "Please turn on Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.BLUETOOTH_CONNECT }, REQ_BLE_CONNECT);
            return;
        }
        try {
            BluetoothDevice dev = ba.getRemoteDevice(ble);
            safeClose();
            if (pb != null) pb.setVisibility(View.VISIBLE);
            if (tvStatus != null) tvStatus.setText("Connecting to my watch\u2026");

            gatt = dev.connectGatt(this, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
                    if (ns == BluetoothProfile.STATE_CONNECTED) {
                        if (ActivityCompat.checkSelfPermission(WearableManagerActivity.this,
                                Manifest.permission.BLUETOOTH_CONNECT)
                                == PackageManager.PERMISSION_GRANTED)
                            g.discoverServices();
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

                @Override
                public void onCharacteristicWrite(BluetoothGatt g,
                        BluetoothGattCharacteristic ch, int status) {
                    // Handle OTA chunk acknowledgement
                    if (waitAck && modelChr != null
                            && ch.getUuid().equals(modelChr.getUuid())) {
                        ackH.removeCallbacksAndMessages(null);
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            waitAck = false;
                            retries = 0;
                            chunkIdx++;
                            final int progress = chunkIdx;
                            final int total    = chunks != null ? chunks.length : 1;
                            // Dispatch UI update and next write to the main thread.
                            // Calling gatt.writeCharacteristic() directly from the
                            // GATT callback thread can cause serialization issues on
                            // some Android versions.
                            ackH.post(() -> {
                                if (pb != null) pb.setProgress(progress);
                                if (tvPct != null)
                                    tvPct.setText((int)(100.0 * progress / total) + "%");
                                sendChunk();
                            });
                        } else {
                            ackH.post(WearableManagerActivity.this::retryChunk);
                        }
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
        ackH.removeCallbacksAndMessages(null);
        waitAck = false;
        if (gatt == null) return;
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED)
                gatt.disconnect();
        } catch (Exception ignored) {}
        try { gatt.close(); } catch (Exception ignored) {}
        gatt = null;
    }

    private void disconnLater() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            safeClose();
            refreshConnUi(false);
        }, DISCONNECT_DELAY_MS);
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

    // ─────────────────────────────────────────────
    //  Utilities
    // ─────────────────────────────────────────────

    private String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024));
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }
}
