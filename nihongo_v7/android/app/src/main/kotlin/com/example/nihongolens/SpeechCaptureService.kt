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
 * SpeechCaptureService  —  PERFORMANCE-FIXED
 *
 * Root-cause fixes for tablet sluggishness / capture dying / LibreTranslate jobs stalling:
 *
 *  FIX 1 – Thread priority
 *    AudioCaptureThread was set to MAX_PRIORITY.  On Android that steals CPU time from the
 *    main thread (UI, MediaPlayer, Chrome renderer) causing the video stuttering / freeze you
 *    see.  Changed to NORM_PRIORITY – the audio read loop is I/O-bound, not CPU-bound.
 *
 *  FIX 2 – Translation rate-limiting / debounce
 *    Every Vosk partial result fired mainHandler.post { processText() }, which in turn
 *    dispatched TWO LibreTranslate HTTP calls (en→hi chain).  A lively audio stream can
 *    produce 15-30 partials per second.  That's 30-60 concurrent HTTP calls piling up in
 *    the executor, saturating the tablet's loopback TCP stack and starving LibreTranslate.
 *    Fix: a 600 ms debounce – only the most-recent text is sent per window, identical to
 *    how a search-as-you-type field works.  Final (non-partial) results still fire instantly.
 *
 *  FIX 3 – Read buffer alignment
 *    readBuf was 4096 bytes but bufSize was maxOf(minBuf*4, 8192).  AudioRecord.read() can
 *    return up to bufSize bytes; anything > readBuf silently truncated the PCM frame and
 *    confused Vosk.  readBuf is now allocated at the same bufSize as the AudioRecord ring.
 *
 *  FIX 4 – WakeLock timeout extended
 *    1-hour cap meant capture silently died on long sessions.  Changed to 4 hours (still
 *    auto-released in onDestroy).  Adjust to taste.
 *
 *  FIX 5 – TranslationManager executor saturation guard
 *    Before submitting to executor, check queue depth (approximate via activeCount vs
 *    corePoolSize) and drop excess work rather than pile it on.  See TranslationManager.
 *
 *  FIX 6 – Vosk model loaded at NORM_PRIORITY
 *    Was NORM_PRIORITY already, kept; just confirmed isDaemon=false stays so it completes
 *    after the app goes background.
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

        private const val TAG          = "SpeechCapture"
        private const val SAMPLE_RATE  = 16_000

        // ── FIX 2: debounce interval for partial results ──────────────────────
        // Final (isFinal=true) results are never debounced.
        private const val PARTIAL_DEBOUNCE_MS = 600L
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

    // FIX 2: pending debounce state
    private var debounceRunnable: Runnable? = null

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
        // FIX 4: 4-hour max instead of 1-hour (released in onDestroy regardless)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::SpeechCapture"
        ).also { it.acquire(4 * 60 * 60 * 1000L) }

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

        // Cancel any pending debounce
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null

        captureThread?.interrupt()
        captureThread = null

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { voskRec?.close() } catch (_: Exception) {}
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
            Log.e(TAG, "AudioRecord state=${ar.state} — not initialized")
            ar.release()
            OverlayService.updateText("", "Audio init failed — tap STOP then START.", "")
            stopSelf(); return
        }
        audioRecord = ar

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
            // FIX 3: readBuf matches the AudioRecord ring buffer size
            val readBuf = ByteArray(bufSize)

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
                        if (isFinal) {
                            // FIX 2a: final results cancel any pending debounce and fire immediately
                            debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                            debounceRunnable = null
                            mainHandler.post { processText(text) }
                        } else {
                            // FIX 2b: partials are debounced — only the last one in the window fires
                            debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                            val r = Runnable { processText(text) }
                            debounceRunnable = r
                            mainHandler.postDelayed(r, PARTIAL_DEBOUNCE_MS)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Vosk error: ${e.message}")
                }
            }
            Log.d(TAG, "Capture thread ended")
        }, "AudioCaptureThread").apply {
            isDaemon = false
            // FIX 1: NORM_PRIORITY — the read loop is I/O-bound.
            // MAX_PRIORITY stole CPU from MediaPlayer/Chrome causing video sluggishness.
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
