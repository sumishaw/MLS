package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean
import org.vosk.Model
import org.vosk.Recognizer

/**
 * SpeechCaptureService — ALL ROOT CAUSES FIXED
 *
 * BUG 1 — Silent zombie: capture thread dies, service stays alive
 *   After the while-loop exits for ANY reason, the original code just logged
 *   "Capture thread ended" and returned. The service stayed running with a
 *   dead thread; isRunning stayed true; the UI showed "STOP" forever;
 *   LibreTranslate got no new work but sat alive looking "hung".
 *   FIX: Track an exitReason string. If the exit was not a clean user-stop,
 *   post stopSelf() + a user-visible error message from the thread.
 *
 * BUG 2 — CPU spin loop on AudioRecord.read() == 0
 *   When MediaProjection is interrupted or audio routing changes on a tablet,
 *   AudioRecord.read() returns 0 continuously (not an error code). The original
 *   "if (read <= 0) continue" spins at ~10,000 iterations/second, burning a
 *   full CPU core. The thermal governor throttles CPU → video stutters; then
 *   the OOM killer terminates the service seconds later.
 *   FIX: Count consecutive zero-reads. After 50 (~250 ms), sleep 20 ms/iter.
 *   After 200 (~4 s total), treat as projection-lost and exit gracefully.
 *
 * BUG 3 — MediaProjection stop not detected on Android 10–13
 *   MediaProjection.Callback was only registered on API 34+ (UPSIDE_DOWN_CAKE).
 *   On Android 10–13 tablets, if the user revokes projection or the system
 *   reclaims it, AudioRecord silently returns 0 → falls into Bug 2.
 *   FIX: Register the callback unconditionally (available since API 21).
 *
 * BUG 4 — Battery optimisation never actually requested at runtime
 *   REQUEST_IGNORE_BATTERY_OPTIMIZATIONS was in the manifest but the runtime
 *   Intent was never fired. Android Doze kills foreground services on Samsung/
 *   Xiaomi/Oppo tablets within 15-60 s of screen-off.
 *   FIX: requestBatteryExemption() called from onStartCommand().
 *
 * BUG 5 — readBuf too small; PCM frames silently truncated
 *   bufSize = maxOf(minBuf*4, 8192) but readBuf = ByteArray(4096).
 *   AudioRecord.read() can return up to bufSize bytes; excess was dropped
 *   silently, confusing Vosk's HMM decoder into producing garbage partials.
 *   FIX: readBuf = ByteArray(bufSize).
 *
 * BUG 6 — Thread.MAX_PRIORITY steals CPU from video playback
 *   AudioRecord.read() is a blocking I/O call; the thread sleeps most of the
 *   time. MAX_PRIORITY preempts MediaPlayer/Chrome decoder threads when they
 *   share a CPU timeslice → video stutters even without the spin-loop.
 *   FIX: Thread.NORM_PRIORITY.
 */
class SpeechCaptureService : Service() {

    companion object {
        const val CHANNEL_ID        = "speech_capture_channel"
        const val NOTIF_ID          = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var isRunning      = false
        @Volatile var targetLanguage = "english"
        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""

        private const val TAG         = "SpeechCapture"
        private const val SAMPLE_RATE = 16_000

        // Debounce for partial results — finals bypass this and fire immediately
        private const val PARTIAL_DEBOUNCE_MS = 600L

        // BUG 2 thresholds
        private const val ZERO_READ_SLEEP_AFTER = 50   // ~250 ms of silence
        private const val ZERO_READ_ABORT_AFTER = 200  // ~4 s → give up
        private const val ZERO_READ_SLEEP_MS    = 20L
    }

    private val mainHandler       = Handler(Looper.getMainLooper())
    private val capturing         = AtomicBoolean(false)
    private val userRequestedStop = AtomicBoolean(false)   // BUG 1 fix

    private var captureThread:   Thread?           = null
    private var audioRecord:     AudioRecord?      = null
    private var mediaProjection: MediaProjection?  = null
    private var voskModel:       Model?            = null
    private var voskRec:         Recognizer?       = null
    private var wakeLock:        PowerManager.WakeLock? = null
    private var lastPushedText   = ""
    private var debounceRunnable: Runnable?        = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Initialising…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Initialising…"))
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(4 * 60 * 60 * 1000L) }   // 4 h max; released in onDestroy

        Log.d(TAG, "onCreate — foreground started, wakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "null intent — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        userRequestedStop.set(false)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No valid MediaProjection token — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        // BUG 4 FIX: actually request battery exemption at runtime
        requestBatteryExemption()

        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        // BUG 3 FIX: register on ALL API levels, not just API 34+
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped externally")
                mainHandler.post {
                    if (!userRequestedStop.get()) {
                        OverlayService.updateText("", "Screen capture was revoked — tap START again.", "")
                        MainActivity.instance?.onCaptureError("Screen capture stopped — tap START again.")
                    }
                    stopSelf()
                }
            }
        }, Handler(Looper.getMainLooper()))

        loadModelThenCapture()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy (userStop=${userRequestedStop.get()})")
        isRunning = false
        capturing.set(false)

        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null

        captureThread?.interrupt()
        captureThread = null

        try { audioRecord?.stop() }   catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { voskRec?.close() }   catch (_: Exception) {}
        try { voskModel?.close() } catch (_: Exception) {}
        voskRec   = null
        voskModel = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        mainHandler.removeCallbacksAndMessages(null)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        TranslationManager.closeAll()

        super.onDestroy()
    }

    // ── Battery exemption (BUG 4 fix) ────────────────────────────────────────

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = android.net.Uri.parse("package:$packageName")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                Log.d(TAG, "Battery exemption dialog launched")
            } else {
                Log.d(TAG, "Already battery-exempt")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Battery exemption request failed: ${e.message}")
        }
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun loadModelThenCapture() {
        if (!ModelDownloadService.isModelReady(this)) {
            Log.w(TAG, "Model not ready — aborting")
            OverlayService.updateText("", "Speech model not ready — download it first, then tap START again.", "")
            stopSelf(); return
        }

        updateNotification("Loading speech model…")
        OverlayService.updateText("", "Loading speech model…", "")

        Thread({
            try {
                val modelPath = ModelDownloadService.modelDir(this).absolutePath
                Log.d(TAG, "Loading Vosk model from: $modelPath")
                voskModel = Model(modelPath)
                Log.d(TAG, "Vosk model loaded OK")
                mainHandler.post { startCapture() }
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}")
                val msg      = e.message ?: ""
                val isCorrupt = msg.contains("No such file", ignoreCase = true)
                        || msg.contains("invalid", ignoreCase = true)
                        || msg.contains("corrupt", ignoreCase = true)
                mainHandler.post {
                    if (isCorrupt) {
                        ModelDownloadService.modelDir(this).deleteRecursively()
                        try { java.io.File(filesDir, "vosk_model_ready").delete() } catch (_: Exception) {}
                        OverlayService.updateText("", "Model corrupted — re-downloading on next launch. Restart the app.", "")
                    } else {
                        OverlayService.updateText("", "Model load error: $msg — close other apps and try again.", "")
                    }
                    stopSelf()
                }
            }
        }, "VoskModelLoader").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    // ── Audio capture ─────────────────────────────────────────────────────────

    private fun startCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            OverlayService.updateText("", "Android 10 or newer required.", "")
            stopSelf(); return
        }

        val projection = mediaProjection ?: run {
            Log.e(TAG, "MediaProjection null at startCapture")
            OverlayService.updateText("", "Screen capture lost — tap STOP then START again.", "")
            stopSelf(); return
        }

        voskModel ?: run { Log.e(TAG, "VoskModel null"); stopSelf(); return }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error: $minBuf")
            OverlayService.updateText("", "Audio init failed — tap STOP then START.", "")
            stopSelf(); return
        }
        // BUG 5 FIX: single source-of-truth for buffer size
        val bufSize = maxOf(minBuf * 4, 8192)

        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val ar = try {
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.Builder failed: ${e.message}")
            OverlayService.updateText("", "Audio setup failed: ${e.message}", "")
            stopSelf(); return
        }

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${ar.state})")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.", "")
            stopSelf(); return
        }
        audioRecord = ar

        voskRec = try {
            Recognizer(voskModel!!, SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer init failed: ${e.message}")
            ar.release(); audioRecord = null
            OverlayService.updateText("", "ASR init error — tap STOP then START.", "")
            stopSelf(); return
        }

        capturing.set(true)
        ar.startRecording()
        updateNotification("Translating video audio…")
        OverlayService.updateText("", "Listening to video audio…", "")
        Log.d(TAG, "Capture started — bufSize=$bufSize")

        captureThread = Thread({
            // BUG 5 FIX: read buffer == ring buffer size
            val readBuf = ByteArray(bufSize)
            var zeroReadCount = 0
            var exitReason    = "clean-stop"   // assume clean until proven otherwise

            try {
                loop@ while (capturing.get() && !Thread.currentThread().isInterrupted) {
                    val rec  = audioRecord ?: run { exitReason = "audioRecord-null"; break@loop }
                    val read = rec.read(readBuf, 0, readBuf.size)

                    when {
                        read == AudioRecord.ERROR_INVALID_OPERATION -> {
                            Log.e(TAG, "AudioRecord: ERROR_INVALID_OPERATION"); exitReason = "audio-error"; break@loop
                        }
                        read == AudioRecord.ERROR_BAD_VALUE -> {
                            Log.e(TAG, "AudioRecord: ERROR_BAD_VALUE"); exitReason = "audio-error"; break@loop
                        }
                        // BUG 2 FIX: count zeros, sleep, eventually abort
                        read <= 0 -> {
                            zeroReadCount++
                            when {
                                zeroReadCount >= ZERO_READ_ABORT_AFTER -> {
                                    Log.e(TAG, "$zeroReadCount consecutive zero reads — projection lost")
                                    exitReason = "zero-read-timeout"
                                    break@loop
                                }
                                zeroReadCount >= ZERO_READ_SLEEP_AFTER ->
                                    Thread.sleep(ZERO_READ_SLEEP_MS)
                            }
                            continue@loop
                        }
                        else -> zeroReadCount = 0
                    }

                    val recognizer = voskRec ?: run { exitReason = "voskRec-null"; break@loop }
                    try {
                        val isFinal = recognizer.acceptWaveForm(readBuf, read)
                        val json    = if (isFinal) recognizer.result else recognizer.partialResult
                        val text    = parseVoskJson(json)

                        if (text.length >= 3 && text != lastPushedText) {
                            lastPushedText = text
                            if (isFinal) {
                                debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                                debounceRunnable = null
                                mainHandler.post { processText(text) }
                            } else {
                                debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                                val r = Runnable { processText(text) }
                                debounceRunnable = r
                                mainHandler.postDelayed(r, PARTIAL_DEBOUNCE_MS)
                            }
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); exitReason = "interrupted"; break@loop
                    } catch (e: Exception) {
                        Log.e(TAG, "Vosk error: ${e.message}")
                        // single-frame errors are recoverable; keep going
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); exitReason = "interrupted"
            } catch (e: Exception) {
                Log.e(TAG, "Capture thread crash: ${e.message}"); exitReason = "crash:${e.message}"
            }

            Log.d(TAG, "Capture thread ended — reason: $exitReason")

            // BUG 1 FIX: non-clean exit → tell the user and stop the zombie service
            val wasClean = userRequestedStop.get() || exitReason == "clean-stop" || exitReason == "interrupted"
            if (!wasClean) {
                val msg = when (exitReason) {
                    "zero-read-timeout" -> "Audio lost (screen capture ended?) — tap START again."
                    "audio-error"       -> "Audio device error — tap START again."
                    else                -> "Capture stopped — tap START again."
                }
                mainHandler.post {
                    OverlayService.updateText("", msg, "")
                    MainActivity.instance?.onCaptureError(msg)
                    stopSelf()
                }
            }

        }, "AudioCaptureThread").apply {
            isDaemon = false
            // BUG 6 FIX: NORM not MAX — read loop is I/O-bound; MAX stole CPU from video
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseVoskJson(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return Regex(""""(?:text|partial)"\s*:\s*"([^"]*?)"""")
            .find(json)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }

    // ── Translation pipeline ──────────────────────────────────────────────────

    private fun processText(text: String) {
        latestOriginal = text
        Log.d(TAG, "Recognised: ${text.take(60)}")
        val wantHindi = targetLanguage == "hindi"

        LanguageDetector.detectLanguage(text) { lang ->
            when {
                lang == "en" || lang == "und" -> {
                    latestEnglish = text
                    if (wantHindi) {
                        TranslationManager.translate(text, "en", "hi") { hi ->
                            latestHindi = hi; pushResult(text, text, hi)
                        }
                    } else {
                        latestHindi = ""; pushResult(text, text, "")
                    }
                }
                lang == "hi" -> {
                    latestHindi = text
                    TranslationManager.translate(text, "hi", "en") { en ->
                        latestEnglish = en
                        pushResult(text, en, if (wantHindi) text else "")
                    }
                }
                else -> {
                    TranslationManager.translate(text, lang, "en") { en ->
                        latestEnglish = en
                        if (wantHindi) {
                            TranslationManager.translate(en, "en", "hi") { hi ->
                                latestHindi = hi; pushResult(text, en, hi)
                            }
                        } else {
                            latestHindi = ""; pushResult(text, en, "")
                        }
                    }
                }
            }
        }
    }

    private fun pushResult(original: String, english: String, hindi: String) {
        OverlayService.updateText(original, english, hindi)
        MainActivity.instance?.onTranslation(original, english, hindi)
        Log.d(TAG, "Pushed → EN: ${english.take(60)}")
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Internal Audio Capture", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Translating")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
