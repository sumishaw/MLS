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
 * SpeechCaptureService
 *
 * Captures INTERNAL device audio (YouTube, VLC, Chrome, offline videos) using
 * MediaProjection + AudioPlaybackCaptureConfiguration and feeds raw PCM to Vosk.
 *
 * KEY CRASH FIXES:
 *  1. startForeground() called synchronously in onCreate() before ANY async work.
 *     On API 29-33 this was missing, causing ForegroundServiceStartNotAllowedException.
 *  2. MediaProjection is obtained inside the service from the raw resultCode/data,
 *     NOT passed as a Parcelable (token is one-use and cannot be marshalled on API 34).
 *  3. AudioRecord.Builder used with AudioPlaybackCaptureConfiguration — never the
 *     deprecated AudioRecord() constructor which requires RECORD_AUDIO on API 34.
 *  4. All AudioRecord/Vosk objects are released on ANY exit path (null-safe cleanup).
 *  5. WakeLock released in a finally block so it never leaks on crash.
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
    }

    private val mainHandler  = Handler(Looper.getMainLooper())
    private val capturing    = AtomicBoolean(false)
    private var captureThread: Thread?          = null
    private var audioRecord:   AudioRecord?     = null
    private var mediaProjection: MediaProjection? = null
    private var voskModel:     Model?           = null
    private var voskRec:       Recognizer?      = null
    private var wakeLock:      PowerManager.WakeLock? = null
    private var lastPushedText = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        // MUST call startForeground() immediately in onCreate() — before ANY async work.
        // On Android 12+ the system kills a service that hasn't called startForeground()
        // within ~10 seconds. Calling it here guarantees we're always within the window.
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

        // Partial WakeLock: keeps CPU alive while screen is off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(60 * 60 * 1000L) }    // max 1 hour

        Log.d(TAG, "onCreate — foreground started, wakeLock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.e(TAG, "onStartCommand received null intent — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "No valid MediaProjection token (resultCode=$resultCode, data=$resultData)")
            stopSelf(); return START_NOT_STICKY
        }

        // Obtain the MediaProjection object inside the service.
        // This MUST happen in onStartCommand (not onCreate) because the token is
        // only valid from the intent that delivered it.
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mgr.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            stopSelf(); return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null after getMediaProjection()")
            stopSelf(); return START_NOT_STICKY
        }

        // Register stop-callback for API 34+ (projection stops externally e.g. user revokes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped externally")
                    mainHandler.post { stopSelf() }
                }
            }, Handler(Looper.getMainLooper()))
        }

        loadModelThenCapture()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isRunning = false
        capturing.set(false)

        // Stop capture thread
        captureThread?.interrupt()
        captureThread = null

        // Release AudioRecord
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        // Release Vosk
        try { voskRec?.close() } catch (_: Exception) {}
        try { voskModel?.close() } catch (_: Exception) {}
        voskRec   = null
        voskModel = null

        // Stop MediaProjection
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        // Cancel pending handlers
        mainHandler.removeCallbacksAndMessages(null)

        // Release WakeLock in a finally-style check
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null

        TranslationManager.closeAll()

        super.onDestroy()
    }

    // ── Model loading ─────────────────────────────────────────────────────────

    private fun loadModelThenCapture() {
        if (!ModelDownloadService.isModelReady(this)) {
            Log.w(TAG, "Model not ready — aborting")
            OverlayService.updateText("", "Speech model not ready — download it first, then tap START again.", "")
            stopSelf(); return
        }

        updateNotification("Loading speech model… (1–2 min first time)")
        OverlayService.updateText("", "Loading speech model…", "")

        Thread({
            try {
                val modelPath = ModelDownloadService.modelDir(this).absolutePath
                Log.d(TAG, "Loading Vosk model from: $modelPath")
                voskModel = Model(modelPath)
                Log.d(TAG, "Vosk model loaded successfully")
                mainHandler.post { startCapture() }
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed: ${e.message}")
                val msg = e.message ?: ""
                val isCorrupt = msg.contains("No such file", ignoreCase = true)
                        || msg.contains("invalid", ignoreCase = true)
                        || msg.contains("corrupt", ignoreCase = true)
                if (isCorrupt) {
                    // Wipe corrupt files so next launch triggers a fresh download
                    ModelDownloadService.modelDir(this).deleteRecursively()
                    try { java.io.File(filesDir, "vosk_model_ready").delete() } catch (_: Exception) {}
                    mainHandler.post {
                        OverlayService.updateText("", "Model corrupted — will re-download on next launch. Restart the app.", "")
                        stopSelf()
                    }
                } else {
                    mainHandler.post {
                        OverlayService.updateText("", "Model load error: $msg — close other apps and try again.", "")
                        stopSelf()
                    }
                }
            }
        }, "VoskModelLoader").apply {
            isDaemon = false          // must finish even if app goes background
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

        val projection = mediaProjection
        if (projection == null) {
            Log.e(TAG, "MediaProjection null at capture start — screen capture was revoked")
            OverlayService.updateText("", "Screen capture lost — tap STOP then START again.", "")
            stopSelf(); return
        }

        val model = voskModel
        if (model == null) {
            Log.e(TAG, "VoskModel null at capture start")
            stopSelf(); return
        }

        // Calculate buffer sizes
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "getMinBufferSize error: $minBuf")
            OverlayService.updateText("", "Audio init failed — tap STOP then START.", "")
            stopSelf(); return
        }
        val bufSize = maxOf(minBuf * 4, 8192)

        // Build AudioPlaybackCaptureConfiguration — captures internal audio,
        // NOT microphone, despite needing RECORD_AUDIO permission
        val captureConfig = android.media.AudioPlaybackCaptureConfiguration
            .Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // Use AudioRecord.Builder — required for AudioPlaybackCaptureConfiguration
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
            Log.e(TAG, "AudioRecord state=${ar.state} — not initialized")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.", "")
            stopSelf(); return
        }
        audioRecord = ar

        // Build Vosk recogniser (full vocabulary — no grammar)
        voskRec = try {
            Recognizer(model, SAMPLE_RATE.toFloat())
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
        Log.d(TAG, "Capture started (bufSize=$bufSize)")

        captureThread = Thread({
            val readBuf = ByteArray(4096)
            while (capturing.get() && !Thread.currentThread().isInterrupted) {
                val rec = audioRecord ?: break
                val read = rec.read(readBuf, 0, readBuf.size)
                if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                    read == AudioRecord.ERROR_BAD_VALUE
                ) {
                    Log.e(TAG, "AudioRecord.read error: $read")
                    break
                }
                if (read <= 0) continue

                val recognizer = voskRec ?: break
                try {
                    val isFinal = recognizer.acceptWaveForm(readBuf, read)
                    val json    = if (isFinal) recognizer.result else recognizer.partialResult
                    val text    = parseVoskJson(json)
                    if (text.length >= 3 && text != lastPushedText) {
                        lastPushedText = text
                        mainHandler.post { processText(text) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Vosk error: ${e.message}")
                    // Continue — single-frame errors are usually recoverable
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply {
            isDaemon = false
            priority = Thread.MAX_PRIORITY
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
                            latestHindi = hi
                            pushResult(text, text, hi)
                        }
                    } else {
                        latestHindi = ""
                        pushResult(text, text, "")
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
                                latestHindi = hi
                                pushResult(text, en, hi)
                            }
                        } else {
                            latestHindi = ""
                            pushResult(text, en, "")
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
            NotificationChannel(
                CHANNEL_ID,
                "Internal Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
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
