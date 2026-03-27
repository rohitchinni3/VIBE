// =============================================================================
// Vibe Watch Firmware v3.11
// TTGO T-Watch 2020 V1 — ESP32 Arduino Core 2.0.14
//
// CRITICAL: BLE includes MUST come BEFORE config.h / LilyGoWatch.h
// This follows the official TTGO SetTimeFromBLE.ino example pattern.
// =============================================================================

// ── 1. BLE includes (MUST be first — before LilyGoWatch.h) ──────────────────
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── 2. TTGO hardware config (pulls in LilyGoWatch.h) ────────────────────────
#include "config.h"

// ── 3. Filesystem ────────────────────────────────────────────────────────────
#include "FS.h"
#include "SPIFFS.h"
using fs::File;   // Fix: 'File' was not declared in scope (TTGO FS namespace)

// ── 4. I2S / DSP ─────────────────────────────────────────────────────────────
#include <driver/i2s.h>
#include <arduinoFFT.h>

// ── 5. TFLite Micro ──────────────────────────────────────────────────────────
#include "tensorflow/lite/micro/micro_error_reporter.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/micro/micro_mutable_op_resolver.h"
#include "tensorflow/lite/schema/schema_generated.h"

// ── 6. Built-in model headers ────────────────────────────────────────────────
// These are placeholder headers; replace with real generated model arrays.
// esp_weights.h  → gate model (voice activity detection)
// MFCC_voice.h   → MFCC speaker-ID model (fallback when no OTA)
// Uncomment when headers are present:
// #include "esp_weights.h"
// #include "MFCC_voice.h"

// ── 7. Standard library ───────────────────────────────────────────────────────
#include <math.h>
#include <string.h>
#include <vector>
#include <algorithm>

// =============================================================================
// Constants & Configuration
// =============================================================================

#define FW_VERSION          "3.11"
#define DISPLAY_SLEEP_MS    8000
#define SAMPLE_RATE         16000
#define I2S_DATA_PIN        2
#define I2S_CLOCK_PIN       0
#define STEP_GOAL           8000

// Color palette (16-bit RGB565)
#define COL_BG      0x0841
#define COL_SURFACE 0x10A3
#define COL_ACCENT  0x3D9F
#define COL_WARN    0xFD40
#define COL_DANGER  0xF800
#define COL_SUCCESS 0x07E0
#define COL_TEXT    0xFFFF

// Run modes
#define RUNMODE_NONVOICE_ONLY   0
#define RUNMODE_FULL_PIPELINE   1

// Inference thresholds
#define GATE_THRESHOLD      0.80f
#define GATE_LATCH_MS       1200
#define NONVOICE_THRESHOLD  0.80f
#define MFCC_THRESHOLD      0.60f

// Audio / DSP
#define SPEC_FFT_SIZE       128
#define SPEC_ROWS           65
#define SPEC_COLS           98
#define MFCC_FRAME_LEN      512
#define MFCC_HOP_LEN        160
#define MFCC_NUM_MELS       32
#define MFCC_NUM_COEFFS     13
#define MFCC_FRAMES         49
#define MFCC_MVN_WINDOW     151
#define PRE_EMPHASIS        0.98f
#define MEL_LOW_HZ          80.0f
#define MEL_HIGH_HZ         8000.0f
#define NOISE_FLOOR_DB      -30.0f

// Memory arenas (PSRAM)
#define GATE_ARENA_SIZE     (50  * 1024)
#define STAGE2_ARENA_SIZE   (70  * 1024)
#define MFCC_ARENA_SIZE     (100 * 1024)
#define OTA_BUF_SIZE        (128 * 1024)
#define MODEL_STORE_SIZE    (128 * 1024)

// BLE UUIDs
#define BLE_DEVICE_NAME         "Vibe-Watch"
#define UUID_STAGE2_SVC         "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define UUID_STAGE2_OTA         "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define UUID_CLASSES_SVC        "d6736a2b-1e3c-4a1b-97e6-6a2a8d8c447a"
#define UUID_CLASSES_CHAR       "7acb2cff-5b5d-4f2d-bf34-cdb39f5e374a"
#define UUID_MFCC_SVC           "d1f6e001-1111-4444-8888-0a0b0c0d0e0f"
#define UUID_MFCC_OTA           "e2f6a002-2222-5555-9999-1a1b1c1d1e1f"
#define UUID_MFCC_CLASSES       "f3f6c003-3333-6666-aaaa-2a2b2c2d2e2f"
#define UUID_TARGET_SVC         "a92c6d12-6c20-4d31-a5a2-6f4e6c0e0001"
#define UUID_TARGET_CHAR        "a92c6d12-6c20-4d31-a5a2-6f4e6c0e0002"

// SPIFFS paths
#define PATH_STAGE2_MODEL   "/model.bin"
#define PATH_STAGE2_CLASSES "/classes.txt"
#define PATH_MFCC_MODEL     "/mfcc_model.bin"
#define PATH_MFCC_CLASSES   "/mfcc_classes.txt"
#define PATH_MFCC_TARGET    "/mfcc_target.txt"
#define PATH_SETTINGS       "/settings.txt"
#define PATH_ALARMS         "/alarm.txt"

// UI screens
enum Screen {
    SCR_HOME = 0,
    SCR_LAUNCHER1,
    SCR_LAUNCHER2,
    SCR_NOTIFICATIONS,
    SCR_ALERT,
    SCR_SOUND_DETECT,
    SCR_VOICE_ID,
    SCR_ACTIVITY,
    SCR_SETTINGS,
    SCR_ALARM,
    SCR_STOPWATCH,
    SCR_TIMER,
    SCR_FLASHLIGHT
};

// =============================================================================
// Global objects
// =============================================================================

TTGOClass     *watch = nullptr;
TFT_eSPI      *tft   = nullptr;

// ── State ────────────────────────────────────────────────────────────────────
volatile Screen   currentScreen    = SCR_HOME;
volatile uint8_t  runMode          = RUNMODE_NONVOICE_ONLY;
volatile bool     bleConnected     = false;
volatile bool     voiceEnabled     = false;
volatile bool     vibrationEnabled = true;
volatile bool     bleEnabled       = true;
volatile bool     displayOn        = true;
volatile uint32_t lastTouchMs      = 0;
volatile bool     alertPending     = false;
String            alertMessage     = "";
String            targetName       = "";
std::vector<String> notifications;

// ── Alarm state ──────────────────────────────────────────────────────────────
struct AlarmSlot { uint8_t hour; uint8_t minute; bool enabled; };
AlarmSlot alarms[3] = {{7,0,false},{8,30,false},{9,0,false}};

// ── Stopwatch ─────────────────────────────────────────────────────────────────
bool     swRunning  = false;
uint32_t swStartMs  = 0;
uint32_t swElapsed  = 0;
uint32_t swLaps[5]  = {};
int      swLapCount = 0;

// ── Timer ─────────────────────────────────────────────────────────────────────
bool     timerRunning   = false;
uint32_t timerTargetMs  = 0;
uint32_t timerStartMs   = 0;
bool     timerDone      = false;
const uint32_t TIMER_PRESETS[] = {60,180,300,600,900,1800}; // seconds
const char*    TIMER_LABELS[]  = {"1m","3m","5m","10m","15m","30m"};
int      timerPreset    = 0;

// ── Brightness ────────────────────────────────────────────────────────────────
uint8_t brightness = 128;

// ── Steps ────────────────────────────────────────────────────────────────────
uint32_t stepCount = 0;

// ── Touch / gesture ──────────────────────────────────────────────────────────
int16_t  swipeStartX = 0, swipeStartY = 0;
uint32_t swipeStartMs = 0;
bool     touchPressed = false;

// ── Double-press PEK ──────────────────────────────────────────────────────────
uint32_t pekLastMs = 0;
bool     pekArmed  = false;

// =============================================================================
// PSRAM-backed buffers
// =============================================================================

static uint8_t *gateArena     = nullptr;
static uint8_t *stage2Arena   = nullptr;
static uint8_t *mfccArena     = nullptr;
static uint8_t *otaBuf        = nullptr;
static uint8_t *stage2Model   = nullptr;
static uint8_t *mfccModel     = nullptr;
static uint32_t otaBufLen     = 0;
static uint32_t stage2ModelLen = 0;
static uint32_t mfccModelLen  = 0;

// ── Audio ring buffers ────────────────────────────────────────────────────────
#define AUDIO_CHUNK      512
// GATE_WINDOW and MFCC_WIN_SIZE are currently equal but kept separate
// so they can be tuned independently as model requirements change.
#define GATE_WINDOW      (MFCC_FRAMES * MFCC_HOP_LEN + MFCC_FRAME_LEN)
#define MFCC_WIN_SIZE    (MFCC_FRAMES * MFCC_HOP_LEN + MFCC_FRAME_LEN)
#define SPEC_WINDOW      (SPEC_COLS * (SPEC_FFT_SIZE / 2))

static int16_t *gateRingBuf  = nullptr;
static int16_t *mfccRingBuf  = nullptr;
static uint32_t gateWrIdx    = 0;
static uint32_t mfccWrIdx    = 0;
SemaphoreHandle_t gateMutex  = nullptr;
SemaphoreHandle_t mfccMutex  = nullptr;

// ── Spectrogram buffer ────────────────────────────────────────────────────────
static float *spectrogramBuf = nullptr; // [SPEC_ROWS][SPEC_COLS]
// specInputBuf: reserved for future int8 DMA/accelerator path (not used by current interpreter path)
static int8_t *specInputBuf  = nullptr;

// ── MFCC buffer ───────────────────────────────────────────────────────────────
static float *mfccBuf        = nullptr; // [MFCC_FRAMES][MFCC_NUM_COEFFS]
// mfccInputBuf: reserved for future int8 DMA/accelerator path (not used by current interpreter path)
static int8_t *mfccInputBuf  = nullptr;

// ── FFT buffers ───────────────────────────────────────────────────────────────
static float *fftReal        = nullptr;
static float *fftImag        = nullptr;

// =============================================================================
// TFLite Micro state
// =============================================================================

tflite::MicroErrorReporter gateErrorReporter;
tflite::MicroErrorReporter stage2ErrorReporter;
tflite::MicroErrorReporter mfccErrorReporter;

const tflite::Model *gateModelPtr   = nullptr;
const tflite::Model *stage2ModelPtr = nullptr;
const tflite::Model *mfccModelPtr   = nullptr;

tflite::MicroInterpreter *gateInterp   = nullptr;
tflite::MicroInterpreter *stage2Interp = nullptr;
tflite::MicroInterpreter *mfccInterp   = nullptr;

tflite::MicroMutableOpResolver<10> gateResolver;
tflite::MicroMutableOpResolver<10> stage2Resolver;
tflite::MicroMutableOpResolver<10> mfccResolver;

// Class name lists
std::vector<String> stage2Classes;
std::vector<String> mfccClasses;

// =============================================================================
// OTA receive state
// =============================================================================

enum OTATarget { OTA_STAGE2_MODEL, OTA_MFCC_MODEL, OTA_NONE };
volatile OTATarget otaActive = OTA_NONE;
volatile bool      otaReady  = false;
static uint8_t    *otaWorkBuf = nullptr; // points into otaBuf

// =============================================================================
// BLE Callbacks
// =============================================================================

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* /*pServer*/) override {
        bleConnected = true;
    }
    void onDisconnect(BLEServer* pServer) override {
        bleConnected = false;
        pServer->startAdvertising();
    }
};

// Generic OTA chunk receiver
class OTACallback : public BLECharacteristicCallbacks {
public:
    OTATarget target;
    explicit OTACallback(OTATarget t) : target(t) {}

    void onWrite(BLECharacteristic* pChar) override {
        std::string val = pChar->getValue();
        if (val.empty()) return;

        // Check for END marker (3-byte sentinel)
        if (val.size() == 3 &&
            val[0] == 'E' && val[1] == 'N' && val[2] == 'D') {
            otaActive = target;
            otaReady  = true;
            return;
        }

        // Append to OTA buffer
        if (otaBufLen + val.size() <= OTA_BUF_SIZE) {
            memcpy(otaBuf + otaBufLen, val.data(), val.size());
            otaBufLen += val.size();
        }
    }
};

// Stage2 class names receiver
class Stage2ClassesCallback : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string val = pChar->getValue();
        stage2Classes.clear();
        String csv = String(val.c_str());
        int start = 0;
        while (start < (int)csv.length()) {
            int comma = csv.indexOf(',', start);
            if (comma < 0) comma = csv.length();
            String tok = csv.substring(start, comma);
            tok.trim();
            if (tok.length()) stage2Classes.push_back(tok);
            start = comma + 1;
        }
        // Save to SPIFFS
        File f = SPIFFS.open(PATH_STAGE2_CLASSES, "w");
        if (f) { f.print(csv); f.close(); }
    }
};

// MFCC class names receiver
class MFCCClassesCallback : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string val = pChar->getValue();
        mfccClasses.clear();
        String csv = String(val.c_str());
        int start = 0;
        while (start < (int)csv.length()) {
            int comma = csv.indexOf(',', start);
            if (comma < 0) comma = csv.length();
            String tok = csv.substring(start, comma);
            tok.trim();
            if (tok.length()) mfccClasses.push_back(tok);
            start = comma + 1;
        }
        File f = SPIFFS.open(PATH_MFCC_CLASSES, "w");
        if (f) { f.print(csv); f.close(); }
    }
};

// Target speaker name receiver
class TargetNameCallback : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string val = pChar->getValue();
        targetName = String(val.c_str());
        targetName.trim();
        File f = SPIFFS.open(PATH_MFCC_TARGET, "w");
        if (f) { f.print(targetName); f.close(); }
    }
};

// =============================================================================
// BLE Setup
// =============================================================================

void setupBLE() {
    BLEDevice::init(BLE_DEVICE_NAME);
    BLEDevice::setMTU(517);

    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    // ── Stage2 OTA service ──────────────────────────────────────────────────
    {
        BLEService *svc = pServer->createService(UUID_STAGE2_SVC);
        BLECharacteristic *ch = svc->createCharacteristic(
            UUID_STAGE2_OTA,
            BLECharacteristic::PROPERTY_WRITE
        );
        ch->addDescriptor(new BLE2902());
        ch->setCallbacks(new OTACallback(OTA_STAGE2_MODEL));
        svc->start();
    }

    // ── Stage2 class names service ──────────────────────────────────────────
    {
        BLEService *svc = pServer->createService(UUID_CLASSES_SVC);
        BLECharacteristic *ch = svc->createCharacteristic(
            UUID_CLASSES_CHAR,
            BLECharacteristic::PROPERTY_WRITE
        );
        ch->addDescriptor(new BLE2902());
        ch->setCallbacks(new Stage2ClassesCallback());
        svc->start();
    }

    // ── MFCC OTA service ────────────────────────────────────────────────────
    {
        BLEService *svc = pServer->createService(UUID_MFCC_SVC);

        BLECharacteristic *otaCh = svc->createCharacteristic(
            UUID_MFCC_OTA,
            BLECharacteristic::PROPERTY_WRITE
        );
        otaCh->addDescriptor(new BLE2902());
        otaCh->setCallbacks(new OTACallback(OTA_MFCC_MODEL));

        BLECharacteristic *classCh = svc->createCharacteristic(
            UUID_MFCC_CLASSES,
            BLECharacteristic::PROPERTY_WRITE
        );
        classCh->addDescriptor(new BLE2902());
        classCh->setCallbacks(new MFCCClassesCallback());

        svc->start();
    }

    // ── Target name service ──────────────────────────────────────────────────
    {
        BLEService *svc = pServer->createService(UUID_TARGET_SVC);
        BLECharacteristic *ch = svc->createCharacteristic(
            UUID_TARGET_CHAR,
            BLECharacteristic::PROPERTY_WRITE
        );
        ch->addDescriptor(new BLE2902());
        ch->setCallbacks(new TargetNameCallback());
        svc->start();
    }

    BLEAdvertising *pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(UUID_STAGE2_SVC);
    pAdv->addServiceUUID(UUID_MFCC_SVC);
    pAdv->setScanResponse(true);
    pAdv->setMinPreferred(0x06);
    BLEDevice::startAdvertising();
}

// =============================================================================
// I2S / Audio
// =============================================================================

void setupI2S() {
    i2s_config_t cfg = {};
    cfg.mode              = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM);
    cfg.sample_rate       = SAMPLE_RATE;
    cfg.bits_per_sample   = I2S_BITS_PER_SAMPLE_16BIT;
    cfg.channel_format    = I2S_CHANNEL_FMT_ONLY_LEFT;
    cfg.communication_format = I2S_COMM_FORMAT_STAND_PCM_SHORT;
    cfg.intr_alloc_flags  = ESP_INTR_FLAG_LEVEL1;
    cfg.dma_buf_count     = 4;
    cfg.dma_buf_len       = AUDIO_CHUNK;
    cfg.use_apll          = false;
    cfg.tx_desc_auto_clear = false;

    i2s_pin_config_t pins = {};
    pins.bck_io_num   = I2S_PIN_NO_CHANGE;
    pins.ws_io_num    = I2S_CLOCK_PIN;
    pins.data_out_num = I2S_PIN_NO_CHANGE;
    pins.data_in_num  = I2S_DATA_PIN;

    i2s_driver_install(I2S_NUM_0, &cfg, 0, nullptr);
    i2s_set_pin(I2S_NUM_0, &pins);
    i2s_zero_dma_buffer(I2S_NUM_0);
}

// =============================================================================
// DSP helpers
// =============================================================================

// Hanning window (cached)
static float hanWin128[SPEC_FFT_SIZE];
static float hanWin512[MFCC_FRAME_LEN];
static bool  dspWinReady = false;

void buildHanningWindows() {
    if (dspWinReady) return;
    for (int i = 0; i < SPEC_FFT_SIZE; i++) {
        hanWin128[i] = 0.5f * (1.0f - cosf(2.0f * M_PI * i / (SPEC_FFT_SIZE - 1)));
    }
    for (int i = 0; i < MFCC_FRAME_LEN; i++) {
        hanWin512[i] = 0.5f * (1.0f - cosf(2.0f * M_PI * i / (MFCC_FRAME_LEN - 1)));
    }
    dspWinReady = true;
}

// Compute mel filterbank centre frequencies (Hz → mel → lin)
static float melFilters[MFCC_NUM_MELS + 2]; // Hz
static bool  melReady = false;

void buildMelFilters() {
    if (melReady) return;
    float lowMel  = 2595.0f * log10f(1.0f + MEL_LOW_HZ  / 700.0f);
    float highMel = 2595.0f * log10f(1.0f + MEL_HIGH_HZ / 700.0f);
    for (int i = 0; i < MFCC_NUM_MELS + 2; i++) {
        float mel = lowMel + (highMel - lowMel) * i / (MFCC_NUM_MELS + 1);
        melFilters[i] = 700.0f * (powf(10.0f, mel / 2595.0f) - 1.0f);
    }
    melReady = true;
}

// Compute spectrogram column from pre-loaded fftReal[0..SPEC_FFT_SIZE-1] floats.
// The caller MUST fill fftReal[] before calling this function.
void computeSpecColumn(float *outCol) {
    // Apply Hanning window in-place
    for (int i = 0; i < SPEC_FFT_SIZE; i++) {
        fftReal[i] *= hanWin128[i];
        fftImag[i]  = 0.0f;
    }
    // FFT via ArduinoFFT
    ArduinoFFT<float> fft(fftReal, fftImag, SPEC_FFT_SIZE, (float)SAMPLE_RATE);
    fft.compute(FFTDirection::Forward);
    // Power spectrum → dB, clamp at noise floor
    for (int i = 0; i < SPEC_ROWS; i++) {
        float pwr = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i];
        float db  = 10.0f * log10f(pwr + 1e-10f);
        outCol[i] = (db < NOISE_FLOOR_DB) ? NOISE_FLOOR_DB : db;
    }
}

// Extract one frame of MFCC (returns MFCC_NUM_COEFFS values in `out`)
void computeMFCCFrame(const int16_t *pcm, float prevSample, float *out) {
    buildMelFilters();
    // Pre-emphasis + window
    for (int i = 0; i < MFCC_FRAME_LEN; i++) {
        float cur  = pcm[i] / 32768.0f;
        float prev = (i == 0) ? prevSample : pcm[i-1] / 32768.0f;
        fftReal[i] = (cur - PRE_EMPHASIS * prev) * hanWin512[i];
        fftImag[i] = 0.0f;
    }
    ArduinoFFT<float> fft(fftReal, fftImag, MFCC_FRAME_LEN, (float)SAMPLE_RATE);
    fft.compute(FFTDirection::Forward);

    // Mel filterbank
    int fftLen   = MFCC_FRAME_LEN / 2 + 1;
    float binHz  = (float)SAMPLE_RATE / MFCC_FRAME_LEN;

    float melEnergy[MFCC_NUM_MELS] = {};
    for (int m = 0; m < MFCC_NUM_MELS; m++) {
        float lo = melFilters[m];
        float ctr= melFilters[m+1];
        float hi = melFilters[m+2];
        for (int k = 0; k < fftLen; k++) {
            float hz  = k * binHz;
            float w   = 0.0f;
            if (hz >= lo && hz <= ctr)  w = (hz - lo) / (ctr - lo + 1e-10f);
            else if (hz > ctr && hz <= hi) w = (hi - hz) / (hi - ctr + 1e-10f);
            float pwr = fftReal[k]*fftReal[k] + fftImag[k]*fftImag[k];
            melEnergy[m] += w * pwr;
        }
        melEnergy[m] = logf(melEnergy[m] + 1e-10f);
    }

    // DCT-II to get MFCC coefficients
    for (int c = 0; c < MFCC_NUM_COEFFS; c++) {
        float s = 0.0f;
        for (int m = 0; m < MFCC_NUM_MELS; m++) {
            s += melEnergy[m] * cosf(M_PI * c * (m + 0.5f) / MFCC_NUM_MELS);
        }
        out[c] = s;
    }
}

// =============================================================================
// TFLite helpers
// =============================================================================

static void addCommonOps(tflite::MicroMutableOpResolver<10> &res) {
    res.AddFullyConnected();
    res.AddDepthwiseConv2D();
    res.AddConv2D();
    res.AddReshape();
    res.AddSoftmax();
    res.AddQuantize();
    res.AddDequantize();
    res.AddMean();
    res.AddMul();
    res.AddAdd();
}

bool loadInterpreter(const uint8_t *modelData, uint32_t modelLen,
                     uint8_t *arena, size_t arenaSize,
                     tflite::MicroMutableOpResolver<10> &resolver,
                     tflite::MicroErrorReporter &reporter,
                     const tflite::Model **outModel,
                     tflite::MicroInterpreter **outInterp) {
    *outModel = tflite::GetModel(modelData);
    if ((*outModel)->version() != TFLITE_SCHEMA_VERSION) return false;
    *outInterp = new tflite::MicroInterpreter(*outModel, resolver, arena, arenaSize, &reporter);
    return (*outInterp)->AllocateTensors() == kTfLiteOk;
}

// =============================================================================
// SPIFFS Persistence
// =============================================================================

void saveSettings() {
    File f = SPIFFS.open(PATH_SETTINGS, "w");
    if (!f) return;
    f.printf("%d,%d,%d\n", (int)voiceEnabled, (int)brightness, (int)vibrationEnabled);
    f.close();
}

void loadSettings() {
    File f = SPIFFS.open(PATH_SETTINGS, "r");
    if (!f) return;
    int ve = 0, br = 128, vib = 1;
    sscanf(f.readStringUntil('\n').c_str(), "%d,%d,%d", &ve, &br, &vib);
    f.close();
    voiceEnabled    = ve;
    brightness      = (uint8_t)constrain(br, 0, 255);
    vibrationEnabled= vib;
}

void saveAlarms() {
    File f = SPIFFS.open(PATH_ALARMS, "w");
    if (!f) return;
    for (int i = 0; i < 3; i++) {
        f.printf("%d,%d,%d\n", alarms[i].hour, alarms[i].minute, (int)alarms[i].enabled);
    }
    f.close();
}

void loadAlarms() {
    File f = SPIFFS.open(PATH_ALARMS, "r");
    if (!f) return;
    for (int i = 0; i < 3; i++) {
        int h = 0, m = 0, en = 0;
        sscanf(f.readStringUntil('\n').c_str(), "%d,%d,%d", &h, &m, &en);
        alarms[i] = {(uint8_t)h, (uint8_t)m, (bool)en};
    }
    f.close();
}

void loadClassesFromSPIFFS(const char *path, std::vector<String> &out) {
    File f = SPIFFS.open(path, "r");
    if (!f) return;
    String csv = f.readString();
    f.close();
    out.clear();
    int start = 0;
    while (start < (int)csv.length()) {
        int comma = csv.indexOf(',', start);
        if (comma < 0) comma = csv.length();
        String tok = csv.substring(start, comma);
        tok.trim();
        if (tok.length()) out.push_back(tok);
        start = comma + 1;
    }
}

bool loadModelFromSPIFFS(const char *path, uint8_t *buf, uint32_t &lenOut) {
    File f = SPIFFS.open(path, "r");
    if (!f) return false;
    size_t sz = f.size();
    if (sz == 0 || sz > MODEL_STORE_SIZE) { f.close(); return false; }
    f.read(buf, sz);
    f.close();
    lenOut = (uint32_t)sz;
    return true;
}

bool saveModelToSPIFFS(const char *path, const uint8_t *buf, uint32_t len) {
    File f = SPIFFS.open(path, "w");
    if (!f) return false;
    f.write(buf, len);
    f.close();
    return true;
}

// =============================================================================
// OTA processing (called from main loop when otaReady == true)
// =============================================================================

void showOTAProgress(const char *label, int pct) {
    tft->fillScreen(COL_BG);
    tft->setTextColor(COL_TEXT);
    tft->setTextSize(2);
    tft->setCursor(20, 80);
    tft->print(label);

    // Progress bar
    int barW = 200, barH = 20;
    int barX = 20, barY = 130;
    tft->drawRect(barX, barY, barW, barH, COL_ACCENT);
    tft->fillRect(barX + 1, barY + 1, (barW - 2) * pct / 100, barH - 2, COL_ACCENT);

    tft->setCursor(100, 160);
    tft->setTextSize(1);
    tft->printf("%d%%", pct);
}

void processOTA() {
    if (!otaReady) return;
    otaReady = false;

    OTATarget tgt = otaActive;
    otaActive = OTA_NONE;

    if (tgt == OTA_STAGE2_MODEL) {
        showOTAProgress("Stage2 OTA", 50);
        saveModelToSPIFFS(PATH_STAGE2_MODEL, otaBuf, otaBufLen);
        memcpy(stage2Model, otaBuf, otaBufLen);
        stage2ModelLen = otaBufLen;
        // Reload interpreter
        delete stage2Interp; stage2Interp = nullptr;
        addCommonOps(stage2Resolver);
        loadInterpreter(stage2Model, stage2ModelLen,
                        stage2Arena, STAGE2_ARENA_SIZE,
                        stage2Resolver, stage2ErrorReporter,
                        &stage2ModelPtr, &stage2Interp);
        showOTAProgress("Stage2 OTA", 100);
    } else if (tgt == OTA_MFCC_MODEL) {
        showOTAProgress("MFCC OTA", 50);
        saveModelToSPIFFS(PATH_MFCC_MODEL, otaBuf, otaBufLen);
        memcpy(mfccModel, otaBuf, otaBufLen);
        mfccModelLen = otaBufLen;
        delete mfccInterp; mfccInterp = nullptr;
        addCommonOps(mfccResolver);
        loadInterpreter(mfccModel, mfccModelLen,
                        mfccArena, MFCC_ARENA_SIZE,
                        mfccResolver, mfccErrorReporter,
                        &mfccModelPtr, &mfccInterp);
        showOTAProgress("MFCC OTA", 100);
    }

    otaBufLen = 0;
    delay(800);
    // Return to home
    currentScreen = SCR_HOME;
}

// =============================================================================
// Vibration helper
// =============================================================================

void vibrateOnce() {
    if (!vibrationEnabled) return;
    watch->motor_begin();
    watch->motor->onec();
}

// =============================================================================
// Display / UI helpers
// =============================================================================

void wakeDisplay() {
    if (!displayOn) {
        watch->openBL();
        watch->bl->adjust(brightness);
        displayOn = true;
    }
    lastTouchMs = millis();
}

void sleepDisplay() {
    watch->closeBL();
    displayOn = false;
}

// Get battery percentage from voltage
int batteryPercent() {
    float v = watch->power->getBattVoltage() / 1000.0f;
    if (v >= 4.2f) return 100;
    if (v <= 3.0f) return 0;
    // Piecewise linear curve
    if (v >= 4.0f) return 80 + (int)((v - 4.0f) / 0.2f * 20);
    if (v >= 3.8f) return 60 + (int)((v - 3.8f) / 0.2f * 20);
    if (v >= 3.6f) return 40 + (int)((v - 3.6f) / 0.2f * 20);
    if (v >= 3.4f) return 20 + (int)((v - 3.4f) / 0.2f * 20);
    return (int)((v - 3.0f) / 0.4f * 20);
}

// Draw top status bar (always 20px at y=0)
void drawStatusBar() {
    tft->fillRect(0, 0, 240, 20, COL_BG);

    // "Vibe" label
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(1);
    tft->setCursor(4, 6);
    tft->print("Vibe");

    // Clock
    RTC_Date dt = watch->rtc->getDateTime();
    tft->setTextColor(COL_TEXT);
    tft->setCursor(85, 6);
    tft->printf("%02d:%02d", dt.hour, dt.minute);

    // Mode dot
    tft->fillCircle(155, 10, 4,
        (runMode == RUNMODE_FULL_PIPELINE) ? COL_ACCENT : COL_SURFACE);

    // BLE dot
    tft->fillCircle(168, 10, 4,
        bleConnected ? 0x001F : COL_SURFACE);

    // Alarm dot (check if any alarm enabled)
    bool anyAlarm = alarms[0].enabled || alarms[1].enabled || alarms[2].enabled;
    tft->fillCircle(181, 10, 4,
        anyAlarm ? COL_WARN : COL_SURFACE);

    // Battery
    int pct = batteryPercent();
    tft->drawRect(200, 5, 30, 12, COL_TEXT);
    tft->fillRect(230, 8, 3, 6, COL_TEXT);
    tft->fillRect(201, 6, max(1,(int)(28 * pct / 100)), 10,
        (pct > 20) ? COL_SUCCESS : COL_DANGER);
}

// =============================================================================
// Screen renderers
// =============================================================================

void drawHome() {
    tft->fillScreen(COL_BG);
    drawStatusBar();

    // Large clock
    RTC_Date dt = watch->rtc->getDateTime();
    tft->setTextColor(COL_TEXT);
    tft->setTextSize(4);
    tft->setCursor(20, 40);
    tft->printf("%02d:%02d", dt.hour, dt.minute);

    // Date
    tft->setTextSize(1);
    tft->setTextColor(COL_ACCENT);
    tft->setCursor(20, 90);
    const char *months[] = {"Jan","Feb","Mar","Apr","May","Jun",
                            "Jul","Aug","Sep","Oct","Nov","Dec"};
    tft->printf("%s %d, %d", months[max(0,dt.month-1)], dt.day, dt.year);

    // Steps card
    tft->fillRoundRect(10, 110, 220, 50, 8, COL_SURFACE);
    tft->setTextColor(COL_TEXT);
    tft->setCursor(16, 120);
    tft->printf("Steps: %lu", (unsigned long)stepCount);
    // Progress bar (grey then accent)
    int barW = 200;
    tft->drawRect(16, 135, barW, 10, COL_SURFACE);
    int fill = min(barW, (int)((long)stepCount * barW / STEP_GOAL));
    tft->fillRect(16, 135, fill, 10, COL_ACCENT);

    // Mode indicator
    tft->setTextSize(1);
    tft->setTextColor(COL_WARN);
    tft->setCursor(10, 170);
    tft->print(runMode == RUNMODE_FULL_PIPELINE ? "Mode: FULL" : "Mode: SOUND");

    // Target name
    if (targetName.length()) {
        tft->setTextColor(COL_ACCENT);
        tft->setCursor(10, 185);
        tft->print("Target: "); tft->print(targetName);
    }
}

void drawLauncher1() {
    tft->fillScreen(COL_BG);
    drawStatusBar();

    struct App { const char *label; uint16_t col; };
    App apps[] = {
        {"Sound", COL_ACCENT},
        {"Voice", COL_SUCCESS},
        {"Activity", COL_WARN},
        {"Settings", COL_SURFACE}
    };
    Screen targets[] = {SCR_SOUND_DETECT, SCR_VOICE_ID, SCR_ACTIVITY, SCR_SETTINGS};
    (void)targets;

    int idx = 0;
    for (int row = 0; row < 2; row++) {
        for (int col = 0; col < 2; col++, idx++) {
            int x = 20 + col * 110;
            int y = 35 + row * 100;
            tft->fillCircle(x + 40, y + 25, 30, apps[idx].col);
            tft->setTextColor(COL_TEXT);
            tft->setTextSize(1);
            tft->setCursor(x + 14, y + 65);
            tft->print(apps[idx].label);
        }
    }
    tft->setTextColor(COL_SURFACE);
    tft->setCursor(105, 228);
    tft->print(">");
}

void drawLauncher2() {
    tft->fillScreen(COL_BG);
    drawStatusBar();

    struct App { const char *label; uint16_t col; };
    App apps[] = {
        {"Alarm",  COL_DANGER},
        {"Watch",  COL_ACCENT},
        {"Timer",  COL_SUCCESS},
        {"Light",  COL_WARN}
    };

    int idx = 0;
    for (int row = 0; row < 2; row++) {
        for (int col = 0; col < 2; col++, idx++) {
            int x = 20 + col * 110;
            int y = 35 + row * 100;
            tft->fillCircle(x + 40, y + 25, 30, apps[idx].col);
            tft->setTextColor(COL_TEXT);
            tft->setTextSize(1);
            tft->setCursor(x + 14, y + 65);
            tft->print(apps[idx].label);
        }
    }
    tft->setTextColor(COL_SURFACE);
    tft->setCursor(100, 228);
    tft->print("<");
}

void drawNotifications() {
    tft->fillScreen(COL_BG);
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 5);
    tft->print("Notifications");
    tft->drawLine(0, 24, 240, 24, COL_SURFACE);

    if (notifications.empty()) {
        tft->setTextColor(COL_SURFACE);
        tft->setTextSize(1);
        tft->setCursor(60, 120);
        tft->print("No events yet");
        return;
    }
    int y = 30;
    int shown = min((int)notifications.size(), 8);
    for (int i = (int)notifications.size() - 1; i >= (int)notifications.size() - shown; i--) {
        tft->setTextColor(COL_TEXT);
        tft->setTextSize(1);
        tft->setCursor(4, y);
        tft->print(notifications[i]);
        y += 24;
        tft->drawLine(0, y - 2, 240, y - 2, COL_SURFACE);
    }
}

void drawAlert() {
    tft->fillScreen(COL_DANGER);
    tft->setTextColor(COL_TEXT);
    tft->setTextSize(3);
    tft->setCursor(20, 70);
    tft->print("ALERT!");
    tft->setTextSize(1);
    tft->setCursor(10, 130);
    tft->print(alertMessage);
    tft->setCursor(60, 200);
    tft->print("[Tap to dismiss]");
}

void drawSoundDetect() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Sound Detect");

    tft->setTextColor(stage2Interp ? COL_SUCCESS : COL_DANGER);
    tft->setTextSize(1);
    tft->setCursor(10, 55);
    tft->print(stage2Interp ? "Model: Loaded" : "Model: None");

    tft->setTextColor(COL_TEXT);
    int y = 75;
    for (const auto &cls : stage2Classes) {
        tft->setCursor(10, y);
        tft->print("• "); tft->print(cls);
        y += 16;
        if (y > 220) break;
    }
}

void drawVoiceID() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Voice ID");

    // Toggle button
    tft->fillRoundRect(10, 55, 100, 32, 8,
        voiceEnabled ? COL_SUCCESS : COL_SURFACE);
    tft->setTextColor(COL_TEXT);
    tft->setTextSize(1);
    tft->setCursor(26, 66);
    tft->print(voiceEnabled ? "Voice ON" : "Voice OFF");

    tft->setTextColor(COL_TEXT);
    tft->setCursor(10, 100);
    tft->print("Target: ");
    tft->print(targetName.length() ? targetName : "(none)");

    tft->setCursor(10, 120);
    tft->print("Enrolled speakers:");
    int y = 140;
    for (const auto &cls : mfccClasses) {
        tft->setCursor(14, y);
        tft->print("• "); tft->print(cls);
        y += 16;
        if (y > 220) break;
    }
}

void drawActivity() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Activity");

    float kcal = stepCount * 0.04f;
    float dist = stepCount * 0.00078f; // km

    tft->setTextColor(COL_TEXT);
    tft->setTextSize(1);
    tft->setCursor(10, 65);  tft->printf("Steps:    %lu / %d", (unsigned long)stepCount, STEP_GOAL);
    tft->setCursor(10, 85);  tft->printf("Calories: %.1f kcal", kcal);
    tft->setCursor(10, 105); tft->printf("Distance: %.2f km", dist);

    // Steps progress
    int bw = 200;
    tft->drawRect(10, 125, bw, 14, COL_ACCENT);
    int fill = min(bw, (int)((long)stepCount * bw / STEP_GOAL));
    tft->fillRect(10, 125, fill, 14, COL_ACCENT);

    // Reset button
    tft->fillRoundRect(70, 185, 100, 32, 8, COL_DANGER);
    tft->setTextColor(COL_TEXT);
    tft->setCursor(95, 196);
    tft->print("Reset");
}

void drawSettings() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Settings");

    tft->setTextSize(1);
    tft->setTextColor(COL_TEXT);

    auto drawToggle = [&](int y, const char *label, bool val) {
        tft->setCursor(10, y);
        tft->print(label);
        tft->fillRoundRect(170, y - 2, 50, 18, 6, val ? COL_SUCCESS : COL_SURFACE);
        tft->setTextColor(COL_TEXT);
        tft->setCursor(183, y + 1);
        tft->print(val ? "ON" : "OFF");
        tft->setTextColor(COL_TEXT);
    };

    drawToggle(55,  "BLE:",        bleEnabled);
    drawToggle(80,  "Voice ID:",   voiceEnabled);
    drawToggle(105, "Vibration:",  vibrationEnabled);

    tft->setCursor(10, 135);
    tft->printf("Run Mode: %s", runMode == RUNMODE_FULL_PIPELINE ? "FULL" : "SOUND");
    tft->setCursor(10, 155);
    tft->print("Target: "); tft->print(targetName.length() ? targetName : "(none)");
    tft->setCursor(10, 175);
    tft->print("Version: "); tft->print(FW_VERSION);
}

void drawAlarm() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Alarms");

    for (int i = 0; i < 3; i++) {
        int y = 55 + i * 60;
        tft->fillRoundRect(5, y, 230, 50, 6, COL_SURFACE);
        tft->setTextColor(COL_TEXT);
        tft->setTextSize(2);
        tft->setCursor(15, y + 10);
        tft->printf("A%d: %02d:%02d", i+1, alarms[i].hour, alarms[i].minute);

        // +/- for hour
        tft->fillRect(100, y + 5,  20, 18, COL_ACCENT);
        tft->setCursor(106, y + 8); tft->setTextSize(1); tft->print("+");
        tft->fillRect(100, y + 27, 20, 18, COL_SURFACE);
        tft->setCursor(106, y + 30); tft->print("-");

        // +/- for minute
        tft->fillRect(130, y + 5,  20, 18, COL_ACCENT);
        tft->setCursor(136, y + 8); tft->print("+");
        tft->fillRect(130, y + 27, 20, 18, COL_SURFACE);
        tft->setCursor(136, y + 30); tft->print("-");

        // toggle
        tft->fillRoundRect(165, y + 12, 55, 22, 6,
            alarms[i].enabled ? COL_SUCCESS : COL_DANGER);
        tft->setTextColor(COL_TEXT);
        tft->setCursor(175, y + 17);
        tft->print(alarms[i].enabled ? "ON" : "OFF");
    }
}

void drawStopwatch() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Stopwatch");

    uint32_t elapsed = swRunning ? (millis() - swStartMs + swElapsed) : swElapsed;
    uint32_t ms  = elapsed % 1000;
    uint32_t sec = (elapsed / 1000) % 60;
    uint32_t min = (elapsed / 60000) % 60;

    tft->setTextSize(3);
    tft->setTextColor(COL_TEXT);
    tft->setCursor(20, 55);
    tft->printf("%02lu:%02lu.%03lu", (unsigned long)min, (unsigned long)sec, (unsigned long)ms);

    // Buttons
    tft->fillRoundRect(10,  120, 80, 30, 6, swRunning ? COL_DANGER : COL_SUCCESS);
    tft->setTextColor(COL_TEXT); tft->setTextSize(1);
    tft->setCursor(30, 130); tft->print(swRunning ? "Stop" : "Start");

    tft->fillRoundRect(100, 120, 60, 30, 6, COL_ACCENT);
    tft->setCursor(115, 130); tft->print("Lap");

    tft->fillRoundRect(170, 120, 60, 30, 6, COL_SURFACE);
    tft->setCursor(183, 130); tft->print("Rst");

    // Laps
    for (int i = 0; i < swLapCount && i < 5; i++) {
        uint32_t lms  = swLaps[i] % 1000;
        uint32_t lsec = (swLaps[i] / 1000) % 60;
        uint32_t lmin = (swLaps[i] / 60000) % 60;
        tft->setCursor(10, 165 + i * 14);
        tft->printf("L%d: %02lu:%02lu.%03lu", i+1,
            (unsigned long)lmin, (unsigned long)lsec, (unsigned long)lms);
    }
}

void drawTimer() {
    tft->fillScreen(COL_BG);
    drawStatusBar();
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(2);
    tft->setCursor(10, 25);
    tft->print("Timer");

    uint32_t remaining = 0;
    if (timerRunning) {
        uint32_t elapsed = millis() - timerStartMs;
        remaining = (elapsed < timerTargetMs) ? (timerTargetMs - elapsed) : 0;
    } else {
        remaining = TIMER_PRESETS[timerPreset] * 1000;
    }

    uint32_t sec = (remaining / 1000) % 60;
    uint32_t min = remaining / 60000;

    tft->setTextSize(4);
    tft->setTextColor(timerDone ? COL_WARN : COL_TEXT);
    tft->setCursor(20, 55);
    tft->printf("%02lu:%02lu", (unsigned long)min, (unsigned long)sec);

    // Preset buttons
    if (!timerRunning) {
        int x = 5;
        for (int i = 0; i < 6; i++) {
            tft->fillRoundRect(x, 130, 34, 22, 4,
                (i == timerPreset) ? COL_ACCENT : COL_SURFACE);
            tft->setTextColor(COL_TEXT); tft->setTextSize(1);
            tft->setCursor(x + 4, 137);
            tft->print(TIMER_LABELS[i]);
            x += 38;
        }
    }

    // Control buttons
    tft->fillRoundRect(10,  170, 80, 30, 6,
        timerRunning ? COL_DANGER : COL_SUCCESS);
    tft->setTextColor(COL_TEXT); tft->setTextSize(1);
    tft->setCursor(28, 180);
    tft->print(timerRunning ? "Cancel" : "Start");

    tft->fillRoundRect(150, 170, 70, 30, 6, COL_SURFACE);
    tft->setCursor(165, 180); tft->print("Reset");
}

void drawFlashlight() {
    tft->fillScreen(TFT_WHITE);
    watch->bl->adjust(255);
}

// =============================================================================
// Touch / gesture handling
// =============================================================================

void handleTouch() {
    if (!watch->touched()) {
        touchPressed = false;
        return;
    }

    int16_t tx, ty;
    watch->getPoint(tx, ty);

    if (!touchPressed) {
        touchPressed  = true;
        swipeStartX   = tx;
        swipeStartY   = ty;
        swipeStartMs  = millis();
        wakeDisplay();
    }

    uint32_t dt = millis() - swipeStartMs;
    int16_t dx  = tx - swipeStartX;
    int16_t dy  = ty - swipeStartY;

    if (dt > 50 && (abs(dx) > 40 || abs(dy) > 40)) {
        touchPressed = false;

        // Dismiss alert on any tap/swipe
        if (currentScreen == SCR_ALERT) {
            alertPending  = false;
            currentScreen = SCR_HOME;
            drawHome();
            return;
        }

        // Swipe left → advance
        if (dx < -40 && abs(dy) < 60) {
            if (currentScreen == SCR_HOME)      { currentScreen = SCR_LAUNCHER1; drawLauncher1(); }
            else if (currentScreen == SCR_LAUNCHER1) { currentScreen = SCR_LAUNCHER2; drawLauncher2(); }
        }
        // Swipe right → back
        else if (dx > 40 && abs(dy) < 60) {
            if (currentScreen == SCR_LAUNCHER2) { currentScreen = SCR_LAUNCHER1; drawLauncher1(); }
            else if (currentScreen != SCR_HOME) { currentScreen = SCR_HOME;      drawHome(); }
        }
        // Swipe down → notifications
        else if (dy > 40 && abs(dx) < 60) {
            if (currentScreen == SCR_HOME) { currentScreen = SCR_NOTIFICATIONS; drawNotifications(); }
        }
        // Swipe up → dismiss notifications
        else if (dy < -40 && abs(dx) < 60) {
            if (currentScreen == SCR_NOTIFICATIONS) { currentScreen = SCR_HOME; drawHome(); }
        }
        return;
    }

    // Short tap (< 200ms, < 20px movement)
    if (dt < 200 && abs(dx) < 20 && abs(dy) < 20) {
        touchPressed = false;
        handleTap(tx, ty);
    }
}

void handleTap(int16_t tx, int16_t ty) {
    if (currentScreen == SCR_ALERT) {
        alertPending = false;
        currentScreen = SCR_HOME;
        drawHome();
        return;
    }

    if (currentScreen == SCR_LAUNCHER1) {
        // 2x2 grid: each cell ~110x100 starting at x=20, y=35
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                int x = 20 + col * 110;
                int y = 35 + row * 100;
                if (tx >= x && tx < x + 100 && ty >= y && ty < y + 90) {
                    int idx = row * 2 + col;
                    Screen apps[] = {SCR_SOUND_DETECT, SCR_VOICE_ID, SCR_ACTIVITY, SCR_SETTINGS};
                    currentScreen = apps[idx];
                    switch (currentScreen) {
                        case SCR_SOUND_DETECT: drawSoundDetect(); break;
                        case SCR_VOICE_ID:     drawVoiceID();     break;
                        case SCR_ACTIVITY:     drawActivity();    break;
                        case SCR_SETTINGS:     drawSettings();    break;
                        default: break;
                    }
                    return;
                }
            }
        }
    }

    if (currentScreen == SCR_LAUNCHER2) {
        Screen apps[] = {SCR_ALARM, SCR_STOPWATCH, SCR_TIMER, SCR_FLASHLIGHT};
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                int x = 20 + col * 110;
                int y = 35 + row * 100;
                if (tx >= x && tx < x + 100 && ty >= y && ty < y + 90) {
                    int idx = row * 2 + col;
                    currentScreen = apps[idx];
                    switch (currentScreen) {
                        case SCR_ALARM:      drawAlarm();      break;
                        case SCR_STOPWATCH:  drawStopwatch();  break;
                        case SCR_TIMER:      drawTimer();      break;
                        case SCR_FLASHLIGHT: drawFlashlight(); break;
                        default: break;
                    }
                    return;
                }
            }
        }
    }

    // Settings toggles
    if (currentScreen == SCR_SETTINGS) {
        if (tx >= 170 && tx <= 220) {
            if (ty >= 53 && ty <= 71) {
                bleEnabled = !bleEnabled;
                if (bleEnabled) setupBLE();
                else BLEDevice::deinit(true);
                saveSettings();
            } else if (ty >= 78 && ty <= 96) {
                voiceEnabled = !voiceEnabled;
                saveSettings();
            } else if (ty >= 103 && ty <= 121) {
                vibrationEnabled = !vibrationEnabled;
                saveSettings();
            }
        }
        drawSettings();
    }

    // Voice ID toggle
    if (currentScreen == SCR_VOICE_ID) {
        if (tx >= 10 && tx <= 110 && ty >= 55 && ty <= 87) {
            voiceEnabled = !voiceEnabled;
            saveSettings();
            drawVoiceID();
        }
    }

    // Activity reset
    if (currentScreen == SCR_ACTIVITY) {
        if (tx >= 70 && tx <= 170 && ty >= 185 && ty <= 217) {
            watch->bma->resetStepCounter();
            stepCount = 0;
            drawActivity();
        }
    }

    // Alarm interactions
    if (currentScreen == SCR_ALARM) {
        for (int i = 0; i < 3; i++) {
            int y = 55 + i * 60;
            // Hour +/-
            if (tx >= 100 && tx <= 120) {
                if (ty >= y + 5 && ty <= y + 23)  { alarms[i].hour = (alarms[i].hour + 1) % 24; }
                if (ty >= y + 27 && ty <= y + 45) { alarms[i].hour = (alarms[i].hour + 23) % 24; }
            }
            // Minute +/-
            if (tx >= 130 && tx <= 150) {
                if (ty >= y + 5 && ty <= y + 23)  { alarms[i].minute = (alarms[i].minute + 1) % 60; }
                if (ty >= y + 27 && ty <= y + 45) { alarms[i].minute = (alarms[i].minute + 59) % 60; }
            }
            // Toggle
            if (tx >= 165 && tx <= 220 && ty >= y + 12 && ty <= y + 34) {
                alarms[i].enabled = !alarms[i].enabled;
                if (alarms[i].enabled) {
                    watch->rtc->setAlarm(alarms[i].minute, alarms[i].hour, 0xFF, 0xFF);
                    watch->rtc->enableAlarm();
                }
            }
        }
        saveAlarms();
        drawAlarm();
    }

    // Stopwatch
    if (currentScreen == SCR_STOPWATCH) {
        // Start/Stop [10,120 → 90,150]
        if (tx >= 10 && tx <= 90 && ty >= 120 && ty <= 150) {
            if (swRunning) {
                swElapsed += millis() - swStartMs;
                swRunning  = false;
            } else {
                swStartMs = millis();
                swRunning = true;
            }
        }
        // Lap [100,120 → 160,150]
        if (tx >= 100 && tx <= 160 && ty >= 120 && ty <= 150 && swRunning) {
            if (swLapCount < 5) {
                swLaps[swLapCount++] = swElapsed + (millis() - swStartMs);
            }
        }
        // Reset [170,120 → 230,150]
        if (tx >= 170 && tx <= 230 && ty >= 120 && ty <= 150) {
            swRunning = false;
            swElapsed = 0;
            swLapCount = 0;
        }
        drawStopwatch();
    }

    // Timer
    if (currentScreen == SCR_TIMER) {
        // Preset selection
        if (!timerRunning && ty >= 130 && ty <= 152) {
            for (int i = 0; i < 6; i++) {
                int x = 5 + i * 38;
                if (tx >= x && tx <= x + 34) { timerPreset = i; break; }
            }
        }
        // Start/Cancel
        if (tx >= 10 && tx <= 90 && ty >= 170 && ty <= 200) {
            if (timerRunning) {
                timerRunning = false;
                timerDone    = false;
            } else {
                timerTargetMs = TIMER_PRESETS[timerPreset] * 1000UL;
                timerStartMs  = millis();
                timerRunning  = true;
                timerDone     = false;
            }
        }
        // Reset
        if (tx >= 150 && tx <= 220 && ty >= 170 && ty <= 200) {
            timerRunning = false;
            timerDone    = false;
        }
        drawTimer();
    }
}

// =============================================================================
// Pedometer
// =============================================================================

void setupPedometer() {
    BMA423_AccelConfig accelCfg;
    accelCfg.odr        = BMA4_OUTPUT_DATA_RATE_100HZ;
    accelCfg.range      = BMA4_ACCEL_RANGE_2G;
    accelCfg.bandwidth  = BMA4_ACCEL_NORMAL_AVG4;
    accelCfg.perf_mode  = BMA4_CONTINOUS_MODE;

    watch->bma->accelConfig(accelCfg);
    watch->bma->enableAccel();
    watch->bma->enableFeature(BMA423_STEP_CNTR, true);
    watch->bma->resetStepCounter();
}

// =============================================================================
// Boot screen
// =============================================================================

void bootStatus(const char *msg) {
    static int bootY = 100;
    tft->setTextColor(COL_TEXT);
    tft->setTextSize(1);
    tft->setCursor(10, bootY);
    tft->print(msg);
    bootY += 16;
}

void drawBootScreen() {
    tft->fillScreen(COL_BG);
    tft->setTextColor(COL_ACCENT);
    tft->setTextSize(4);
    tft->setCursor(50, 30);
    tft->print("Vibe");
    tft->setTextColor(COL_TEXT);
    tft->setTextSize(1);
    tft->setCursor(80, 75);
    tft->print("v"); tft->print(FW_VERSION);
}

// =============================================================================
// Audio capture task (Core 1)
// =============================================================================

void audioTask(void *pvParam) {
    static int16_t audioBuf[AUDIO_CHUNK];
    size_t bytesRead = 0;

    while (true) {
        esp_err_t err = i2s_read(I2S_NUM_0, audioBuf,
                                 AUDIO_CHUNK * sizeof(int16_t),
                                 &bytesRead, pdMS_TO_TICKS(50));
        if (err != ESP_OK || bytesRead == 0) continue;

        int samples = bytesRead / sizeof(int16_t);

        // Write into gate ring buffer
        if (xSemaphoreTake(gateMutex, pdMS_TO_TICKS(5)) == pdTRUE) {
            for (int i = 0; i < samples; i++) {
                gateRingBuf[gateWrIdx % (GATE_WINDOW * 2)] = audioBuf[i];
                gateWrIdx++;
            }
            xSemaphoreGive(gateMutex);
        }

        // Write into MFCC ring buffer
        if (xSemaphoreTake(mfccMutex, pdMS_TO_TICKS(5)) == pdTRUE) {
            for (int i = 0; i < samples; i++) {
                mfccRingBuf[mfccWrIdx % (MFCC_WIN_SIZE * 2)] = audioBuf[i];
                mfccWrIdx++;
            }
            xSemaphoreGive(mfccMutex);
        }
    }
}

// =============================================================================
// ML inference
// =============================================================================

float runGateModel() {
    if (!gateInterp) return 0.0f;

    // Copy latest GATE_WINDOW samples
    static float frameFloat[MFCC_FRAMES * MFCC_NUM_COEFFS];
    if (gateWrIdx < (uint32_t)GATE_WINDOW) return 0.0f;

    // ── Critical section: copy only, no computation ──────────────────────────
    xSemaphoreTake(gateMutex, portMAX_DELAY);
    uint32_t base = gateWrIdx - GATE_WINDOW;
    static int16_t framePCM[GATE_WINDOW];
    for (uint32_t i = 0; i < GATE_WINDOW; i++) {
        framePCM[i] = gateRingBuf[(base + i) % (GATE_WINDOW * 2)];
    }
    // Capture the last sample as prevSample for pre-emphasis while still in critical section
    float prevSamp = (GATE_WINDOW > 0) ? framePCM[0] / 32768.0f : 0.0f;
    xSemaphoreGive(gateMutex);
    // ── End critical section ─────────────────────────────────────────────────

    // Compute MFCC_FRAMES frames of MFCC (mutex already released)
    buildHanningWindows();
    buildMelFilters();
    for (int f = 0; f < MFCC_FRAMES; f++) {
        int offset = f * MFCC_HOP_LEN;
        computeMFCCFrame(&framePCM[offset], prevSamp, &frameFloat[f * MFCC_NUM_COEFFS]);
        prevSamp = framePCM[offset + MFCC_FRAME_LEN - 1] / 32768.0f;
    }

    // Quantize and fill input tensor
    TfLiteTensor *input = gateInterp->input(0);
    float scale  = input->params.scale;
    int32_t zp   = input->params.zero_point;
    for (int i = 0; i < MFCC_FRAMES * MFCC_NUM_COEFFS; i++) {
        int q = (int)(frameFloat[i] / scale) + zp;
        input->data.int8[i] = (int8_t)constrain(q, -128, 127);
    }

    if (gateInterp->Invoke() != kTfLiteOk) return 0.0f;

    TfLiteTensor *out = gateInterp->output(0);
    float outScale = out->params.scale;
    int32_t outZP  = out->params.zero_point;
    return (out->data.int8[1] - outZP) * outScale;
}

float runStage2Model() {
    if (!stage2Interp) return 0.0f;

    uint32_t needed = SPEC_COLS * (SPEC_FFT_SIZE / 2) + SPEC_FFT_SIZE;
    if (gateWrIdx < needed) return 0.0f;

    // Allocate a temporary PCM copy on the stack (SPEC_FFT_SIZE per column × SPEC_COLS)
    // Use a static buffer to avoid stack overflow on embedded target.
    static int16_t specPCM[SPEC_COLS * SPEC_FFT_SIZE];

    // ── Critical section: copy only, no computation ──────────────────────────
    xSemaphoreTake(gateMutex, portMAX_DELAY);
    uint32_t base = gateWrIdx - SPEC_COLS * (SPEC_FFT_SIZE / 2);
    for (int col = 0; col < SPEC_COLS; col++) {
        for (int i = 0; i < SPEC_FFT_SIZE; i++) {
            specPCM[col * SPEC_FFT_SIZE + i] =
                gateRingBuf[(base + col * (SPEC_FFT_SIZE / 2) + i) % (GATE_WINDOW * 2)];
        }
    }
    xSemaphoreGive(gateMutex);
    // ── End critical section ─────────────────────────────────────────────────

    // FFT computed outside the critical section
    for (int col = 0; col < SPEC_COLS; col++) {
        for (int i = 0; i < SPEC_FFT_SIZE; i++) {
            fftReal[i] = specPCM[col * SPEC_FFT_SIZE + i] / 32768.0f;
        }
        computeSpecColumn(&spectrogramBuf[col * SPEC_ROWS]);
    }

    TfLiteTensor *input = stage2Interp->input(0);
    float scale = input->params.scale;
    int32_t zp  = input->params.zero_point;
    for (int i = 0; i < SPEC_ROWS * SPEC_COLS; i++) {
        int q = (int)(spectrogramBuf[i] / scale) + zp;
        input->data.int8[i] = (int8_t)constrain(q, -128, 127);
    }

    if (stage2Interp->Invoke() != kTfLiteOk) return 0.0f;

    TfLiteTensor *out = stage2Interp->output(0);
    float outScale = out->params.scale;
    int32_t outZP  = out->params.zero_point;

    // Find highest score
    float best = 0.0f;
    int   bestIdx = 0;
    for (int i = 0; i < out->dims->data[out->dims->size - 1]; i++) {
        float score = (out->data.int8[i] - outZP) * outScale;
        if (score > best) { best = score; bestIdx = i; }
    }

    if (best >= NONVOICE_THRESHOLD) {
        String cls = (bestIdx < (int)stage2Classes.size()) ?
                     stage2Classes[bestIdx] : String("Sound#") + bestIdx;
        alertMessage = cls;
        return best;
    }
    return 0.0f;
}

String runMFCCModel() {
    if (!mfccInterp) return "";
    if (mfccWrIdx < (uint32_t)MFCC_WIN_SIZE) return "";

    xSemaphoreTake(mfccMutex, portMAX_DELAY);
    uint32_t base = mfccWrIdx - MFCC_WIN_SIZE;
    static int16_t pcmBuf[MFCC_WIN_SIZE];
    for (uint32_t i = 0; i < MFCC_WIN_SIZE; i++) {
        pcmBuf[i] = mfccRingBuf[(base + i) % (MFCC_WIN_SIZE * 2)];
    }
    xSemaphoreGive(mfccMutex);

    buildHanningWindows();
    buildMelFilters();

    // Compute MFCC frames with running MVN
    float mvnMean[MFCC_NUM_COEFFS] = {};
    float mvnVar [MFCC_NUM_COEFFS] = {};
    int   mvnN   = 0;

    for (int f = 0; f < MFCC_FRAMES; f++) {
        int offset = f * MFCC_HOP_LEN;
        float frame[MFCC_NUM_COEFFS];
        float prev = (offset > 0) ? pcmBuf[offset - 1] / 32768.0f : 0.0f;
        computeMFCCFrame(&pcmBuf[offset], prev, frame);
        memcpy(&mfccBuf[f * MFCC_NUM_COEFFS], frame, sizeof(frame));

        // Running mean — accumulate across all MFCC_FRAMES
        mvnN++;
        for (int c = 0; c < MFCC_NUM_COEFFS; c++) mvnMean[c] += frame[c];
    }
    for (int c = 0; c < MFCC_NUM_COEFFS; c++) mvnMean[c] /= MFCC_FRAMES;
    for (int f = 0; f < MFCC_FRAMES; f++) {
        for (int c = 0; c < MFCC_NUM_COEFFS; c++) {
            float d = mfccBuf[f * MFCC_NUM_COEFFS + c] - mvnMean[c];
            mvnVar[c] += d * d;
        }
    }
    for (int c = 0; c < MFCC_NUM_COEFFS; c++) {
        mvnVar[c] = sqrtf(mvnVar[c] / MFCC_FRAMES + 1e-8f);
    }
    // Normalize
    for (int f = 0; f < MFCC_FRAMES; f++) {
        for (int c = 0; c < MFCC_NUM_COEFFS; c++) {
            mfccBuf[f * MFCC_NUM_COEFFS + c] =
                (mfccBuf[f * MFCC_NUM_COEFFS + c] - mvnMean[c]) / mvnVar[c];
        }
    }

    TfLiteTensor *input = mfccInterp->input(0);
    float scale = input->params.scale;
    int32_t zp  = input->params.zero_point;
    for (int i = 0; i < MFCC_FRAMES * MFCC_NUM_COEFFS; i++) {
        int q = (int)(mfccBuf[i] / scale) + zp;
        input->data.int8[i] = (int8_t)constrain(q, -128, 127);
    }

    if (mfccInterp->Invoke() != kTfLiteOk) return "";

    TfLiteTensor *out = mfccInterp->output(0);
    float outScale = out->params.scale;
    int32_t outZP  = out->params.zero_point;

    float best = 0.0f;
    int   bestIdx = 0;
    for (int i = 0; i < out->dims->data[out->dims->size - 1]; i++) {
        float score = (out->data.int8[i] - outZP) * outScale;
        if (score > best) { best = score; bestIdx = i; }
    }

    if (best >= MFCC_THRESHOLD) {
        return (bestIdx < (int)mfccClasses.size()) ?
               mfccClasses[bestIdx] : String("Speaker#") + bestIdx;
    }
    return "";
}

// =============================================================================
// ML pipeline loop (called from main loop, Core 0)
// =============================================================================

static uint32_t gateLatchStart = 0;
static bool     gateLatched    = false;
static uint32_t mlLastMs       = 0;

void mlPipelineUpdate() {
    // Run at most every 500ms to avoid overloading CPU
    if (millis() - mlLastMs < 500) return;
    mlLastMs = millis();

    if (runMode == RUNMODE_NONVOICE_ONLY) {
        float score = runStage2Model();
        if (score >= NONVOICE_THRESHOLD) {
            notifications.push_back(alertMessage + " " + String(score * 100, 0) + "%");
            alertPending  = true;
            vibrateOnce();
            wakeDisplay();
            currentScreen = SCR_ALERT;
            drawAlert();
        }
    } else {
        // Full pipeline: gate first
        float gateScore = runGateModel();
        if (gateScore >= GATE_THRESHOLD) {
            if (!gateLatched) { gateLatched = true; gateLatchStart = millis(); }
        }

        if (gateLatched) {
            if (millis() - gateLatchStart >= (uint32_t)GATE_LATCH_MS) {
                gateLatched = false;
            }
            // Run MFCC speaker ID
            String who = runMFCCModel();
            if (who.length()) {
                if (targetName.length() == 0 || who == targetName) {
                    alertMessage  = "Voice: " + who;
                    notifications.push_back(alertMessage);
                    alertPending  = true;
                    vibrateOnce();
                    wakeDisplay();
                    currentScreen = SCR_ALERT;
                    drawAlert();
                }
            }
        } else {
            // No voice — run stage2 sound classification
            float score = runStage2Model();
            if (score >= NONVOICE_THRESHOLD) {
                notifications.push_back(alertMessage + " " + String(score * 100, 0) + "%");
                alertPending  = true;
                vibrateOnce();
                wakeDisplay();
                currentScreen = SCR_ALERT;
                drawAlert();
            }
        }
    }
}

// =============================================================================
// Alarm check
// =============================================================================

void checkAlarms() {
    static uint32_t alarmLastMs = 0;
    if (millis() - alarmLastMs < 10000) return;
    alarmLastMs = millis();

    RTC_Date dt = watch->rtc->getDateTime();
    for (int i = 0; i < 3; i++) {
        if (alarms[i].enabled &&
            alarms[i].hour   == dt.hour &&
            alarms[i].minute == dt.minute) {
            alertMessage  = String("Alarm ") + (i+1);
            alertPending  = true;
            vibrateOnce();
            wakeDisplay();
            notifications.push_back(alertMessage);
            currentScreen = SCR_ALERT;
            drawAlert();
        }
    }
}

// =============================================================================
// Timer check
// =============================================================================

void checkTimer() {
    if (!timerRunning) return;
    uint32_t elapsed = millis() - timerStartMs;
    if (elapsed >= timerTargetMs && !timerDone) {
        timerDone    = true;
        timerRunning = false;
        vibrateOnce();
        wakeDisplay();
        alertMessage  = "Timer done!";
        alertPending  = true;
        notifications.push_back(alertMessage);
        currentScreen = SCR_ALERT;
        drawAlert();
    }
}

// =============================================================================
// PEK button handler
// =============================================================================

void handlePEK() {
    // AXP202 PEK short press
    if (watch->power->isPEKShortPressIRQ()) {
        watch->power->clearIRQ();
        if (!displayOn) {
            wakeDisplay();
        } else {
            // Arm double-press detection
            uint32_t now = millis();
            if (pekArmed && (now - pekLastMs) < 500) {
                // Double press → toggle run mode
                runMode = (runMode == RUNMODE_NONVOICE_ONLY) ?
                          RUNMODE_FULL_PIPELINE : RUNMODE_NONVOICE_ONLY;
                pekArmed = false;
                if (currentScreen == SCR_HOME) drawHome();
            } else {
                pekArmed  = true;
                pekLastMs = now;
            }
        }
    }
    // Reset pek arm after timeout
    if (pekArmed && (millis() - pekLastMs) >= 500) {
        pekArmed = false;
    }
}

// =============================================================================
// Auto-sleep check
// =============================================================================

void checkAutoSleep() {
    if (displayOn && (millis() - lastTouchMs) > DISPLAY_SLEEP_MS) {
        sleepDisplay();
    }
}

// =============================================================================
// UI refresh (only on appropriate screens)
// =============================================================================

static uint32_t uiLastMs = 0;

void refreshUI() {
    uint32_t now = millis();
    // Refresh home every 5s, stopwatch every 100ms, timer every 500ms, others on-demand
    uint32_t interval = 5000;
    if (currentScreen == SCR_STOPWATCH) interval = 100;
    else if (currentScreen == SCR_TIMER) interval = 500;

    if (now - uiLastMs < interval) return;
    uiLastMs = now;

    if (!displayOn) return;

    switch (currentScreen) {
        case SCR_HOME:         drawHome();        break;
        case SCR_STOPWATCH:    drawStopwatch();   break;
        case SCR_TIMER:        drawTimer();        break;
        default: break;
    }
}

// =============================================================================
// setup()
// =============================================================================

void setup() {
    Serial.begin(115200);

    // Init watch hardware
    watch = TTGOClass::getWatch();
    watch->begin();
    watch->openBL();

    tft = watch->tft;
    tft->fillScreen(COL_BG);

    watch->bl->adjust(brightness);

    // Boot screen
    drawBootScreen();
    bootStatus("Initializing SPIFFS...");

    // SPIFFS
    if (!SPIFFS.begin(true)) {
        bootStatus("SPIFFS mount FAILED");
    } else {
        bootStatus("SPIFFS OK");
    }

    // Load settings
    bootStatus("Loading settings...");
    loadSettings();
    loadAlarms();

    // Load classes
    bootStatus("Loading class labels...");
    loadClassesFromSPIFFS(PATH_STAGE2_CLASSES, stage2Classes);
    loadClassesFromSPIFFS(PATH_MFCC_CLASSES,   mfccClasses);

    // Load target name
    {
        File f = SPIFFS.open(PATH_MFCC_TARGET, "r");
        if (f) { targetName = f.readString(); targetName.trim(); f.close(); }
    }

    // Allocate PSRAM buffers
    bootStatus("Allocating PSRAM...");
    gateArena     = (uint8_t*)ps_malloc(GATE_ARENA_SIZE);
    stage2Arena   = (uint8_t*)ps_malloc(STAGE2_ARENA_SIZE);
    mfccArena     = (uint8_t*)ps_malloc(MFCC_ARENA_SIZE);
    otaBuf        = (uint8_t*)ps_malloc(OTA_BUF_SIZE);
    stage2Model   = (uint8_t*)ps_malloc(MODEL_STORE_SIZE);
    mfccModel     = (uint8_t*)ps_malloc(MODEL_STORE_SIZE);
    gateRingBuf   = (int16_t*)ps_malloc(GATE_WINDOW * 2 * sizeof(int16_t));
    mfccRingBuf   = (int16_t*)ps_malloc(MFCC_WIN_SIZE * 2 * sizeof(int16_t));
    spectrogramBuf= (float*)ps_malloc(SPEC_ROWS * SPEC_COLS * sizeof(float));
    specInputBuf  = (int8_t*)ps_malloc(SPEC_ROWS * SPEC_COLS * sizeof(int8_t));
    mfccBuf       = (float*)ps_malloc(MFCC_FRAMES * MFCC_NUM_COEFFS * sizeof(float));
    mfccInputBuf  = (int8_t*)ps_malloc(MFCC_FRAMES * MFCC_NUM_COEFFS * sizeof(int8_t));
    fftReal       = (float*)ps_malloc(MFCC_FRAME_LEN * sizeof(float));
    fftImag       = (float*)ps_malloc(MFCC_FRAME_LEN * sizeof(float));

    if (!gateArena || !stage2Arena || !mfccArena || !otaBuf ||
        !stage2Model || !mfccModel || !gateRingBuf || !mfccRingBuf ||
        !spectrogramBuf || !mfccBuf || !fftReal || !fftImag) {
        bootStatus("PSRAM alloc FAILED!");
    } else {
        bootStatus("PSRAM OK");
    }

    memset(gateRingBuf,  0, GATE_WINDOW * 2 * sizeof(int16_t));
    memset(mfccRingBuf,  0, MFCC_WIN_SIZE * 2 * sizeof(int16_t));

    // FreeRTOS mutexes
    gateMutex = xSemaphoreCreateMutex();
    mfccMutex = xSemaphoreCreateMutex();

    // Build DSP lookup tables
    buildHanningWindows();
    buildMelFilters();

    // TFLite op resolvers
    addCommonOps(gateResolver);
    addCommonOps(stage2Resolver);
    addCommonOps(mfccResolver);

    // Load models from SPIFFS (gate model from header — placeholder)
    bootStatus("Loading models...");
    // Gate model: normally loaded from esp_weights.h
    // Uncomment when esp_weights.h is available:
    // loadInterpreter(g_model_data, g_model_data_len,
    //                 gateArena, GATE_ARENA_SIZE,
    //                 gateResolver, gateErrorReporter,
    //                 &gateModelPtr, &gateInterp);

    // Stage2 model from SPIFFS
    if (loadModelFromSPIFFS(PATH_STAGE2_MODEL, stage2Model, stage2ModelLen)) {
        loadInterpreter(stage2Model, stage2ModelLen,
                        stage2Arena, STAGE2_ARENA_SIZE,
                        stage2Resolver, stage2ErrorReporter,
                        &stage2ModelPtr, &stage2Interp);
        bootStatus("Stage2 model OK");
    } else {
        bootStatus("Stage2 model: not found");
    }

    // MFCC model from SPIFFS (or fall back to MFCC_voice.h)
    if (loadModelFromSPIFFS(PATH_MFCC_MODEL, mfccModel, mfccModelLen)) {
        loadInterpreter(mfccModel, mfccModelLen,
                        mfccArena, MFCC_ARENA_SIZE,
                        mfccResolver, mfccErrorReporter,
                        &mfccModelPtr, &mfccInterp);
        bootStatus("MFCC model OK");
    } else {
        bootStatus("MFCC model: not found");
        // Fallback to MFCC_voice.h when available:
        // loadInterpreter(g_mfcc_model_data, g_mfcc_model_data_len,
        //                 mfccArena, MFCC_ARENA_SIZE,
        //                 mfccResolver, mfccErrorReporter,
        //                 &mfccModelPtr, &mfccInterp);
    }

    // Pedometer
    bootStatus("Setup pedometer...");
    setupPedometer();

    // I2S microphone
    bootStatus("Setup I2S...");
    setupI2S();

    // BLE
    if (bleEnabled) {
        bootStatus("Starting BLE...");
        setupBLE();
        bootStatus("BLE started");
    }

    // RTC
    watch->rtc->check();

    // IRQ for PEK and alarm
    watch->power->enableIRQ(AXP202_PEK_SHORTPRESS_IRQ, true);
    watch->power->clearIRQ();

    // Audio capture task on Core 1
    xTaskCreatePinnedToCore(
        audioTask,
        "audio",
        4096,
        nullptr,
        1,
        nullptr,
        1  // Core 1
    );

    // Brief boot screen pause
    delay(1200);

    // Enable touch
    watch->openBL();
    watch->bl->adjust(brightness);
    lastTouchMs = millis();
    displayOn   = true;

    currentScreen = SCR_HOME;
    drawHome();
}

// =============================================================================
// loop()
// =============================================================================

void loop() {
    // PEK button
    handlePEK();

    // Touch / gesture
    handleTouch();

    // Alarms
    checkAlarms();

    // Timer
    checkTimer();

    // Auto-sleep
    checkAutoSleep();

    // Step counter
    static uint32_t stepsLastMs = 0;
    if (millis() - stepsLastMs > 2000) {
        stepsLastMs = millis();
        uint32_t cnt = 0;
        watch->bma->getCounter(&cnt);
        stepCount = cnt;
    }

    // OTA processing
    processOTA();

    // ML pipeline (non-blocking, rate-limited internally)
    mlPipelineUpdate();

    // UI refresh
    refreshUI();

    delay(10);
}
