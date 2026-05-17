package com.example.nihongolens

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * MainActivity
 *
 * KEY FIX — crash on "Start Capture":
 *   The original code called requestMediaProjection() inside onRequestPermissionsResult(),
 *   which started a NEW Activity result before the old one finished, leaving
 *   pendingProjectionResult in an inconsistent state that crashed on API 34.
 *
 *   Fix: separate request codes, guard every result dispatch with a null check,
 *   and ensure we NEVER deliver two MethodChannel results from one call.
 */
class MainActivity : FlutterActivity() {

    companion object {
        @Volatile var instance: MainActivity? = null

        private const val REQ_MEDIA_PROJECTION = 200
        private const val REQ_AUDIO_PERMISSION  = 100
        private const val TAG                   = "MainActivity"
    }

    private val CHANNEL = "overlay_channel"
    private var methodChannel: MethodChannel? = null

    // Only ONE pending result at a time — guarded at every write/read site
    @Volatile private var pendingProjectionResult: MethodChannel.Result? = null

    // ── Download progress broadcast receiver ──────────────────────────────────

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ModelDownloadService.ACTION_DOWNLOAD_PROGRESS -> {
                    val pct = intent.getIntExtra(ModelDownloadService.EXTRA_PROGRESS_PCT, 0)
                    val mb  = intent.getLongExtra(ModelDownloadService.EXTRA_DOWNLOADED_MB, 0L)
                    val tot = intent.getLongExtra(ModelDownloadService.EXTRA_TOTAL_MB, 0L)
                    runOnUiThread {
                        methodChannel?.invokeMethod("onDownloadProgress", mapOf(
                            "percent"      to pct,
                            "downloadedMb" to mb,
                            "totalMb"      to tot
                        ))
                    }
                }
                ModelDownloadService.ACTION_MODEL_READY -> {
                    runOnUiThread { methodChannel?.invokeMethod("onModelReady", null) }
                }
                ModelDownloadService.ACTION_MODEL_ERROR -> {
                    val msg = intent.getStringExtra(ModelDownloadService.EXTRA_ERROR_MSG) ?: "Unknown error"
                    runOnUiThread {
                        methodChannel?.invokeMethod("onModelError", mapOf("message" to msg))
                    }
                }
            }
        }
    }

    // ── Flutter method channel setup ──────────────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        instance = this

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {

                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "requestOverlayPermission" -> {
                    if (!Settings.canDrawOverlays(this)) {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                        result.success(false)
                    } else {
                        result.success(true)
                    }
                }

                "hasAudioPermission" ->
                    result.success(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                    )

                "requestAudioPermission" ->
                    requestAudioThenProjection(result)

                // No-op stubs (accessibility not needed for internal-audio capture)
                "checkAccessibilityEnabled" -> result.success(true)
                "openAccessibilitySettings" -> result.success(true)

                "isModelReady" ->
                    result.success(ModelDownloadService.isModelReady(this))

                "getModelStatus" ->
                    result.success(
                        if (ModelDownloadService.isModelReady(this)) "ready" else "not_downloaded"
                    )

                "startModelDownload" -> {
                    val force = call.argument<Boolean>("forceRedownload") ?: false
                    if (force || !ModelDownloadService.isModelReady(this)) {
                        val i = Intent(this, ModelDownloadService::class.java)
                        startForegroundServiceCompat(i)
                        result.success(true)
                    } else {
                        result.success(false) // already ready
                    }
                }

                "startOverlay" -> {
                    val i = Intent(this, OverlayService::class.java)
                    startForegroundServiceCompat(i)
                    result.success(true)
                }

                "stopOverlay" -> {
                    stopService(Intent(this, OverlayService::class.java))
                    result.success(true)
                }

                // "startSpeechCapture" triggers audio permission → media projection
                "startSpeechCapture" ->
                    requestAudioThenProjection(result)

                "stopSpeechCapture" -> {
                    stopService(Intent(this, SpeechCaptureService::class.java))
                    result.success(true)
                }

                "isSpeechCaptureRunning" ->
                    result.success(SpeechCaptureService.isRunning)

                "setTargetLanguage" -> {
                    val lang = call.argument<String>("language") ?: "english"
                    SpeechCaptureService.targetLanguage = lang
                    result.success(true)
                }

                "getLatestTranslation" ->
                    result.success(mapOf(
                        "original" to SpeechCaptureService.latestOriginal,
                        "english"  to SpeechCaptureService.latestEnglish,
                        "hindi"    to SpeechCaptureService.latestHindi
                    ))

                else -> result.notImplemented()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction(ModelDownloadService.ACTION_DOWNLOAD_PROGRESS)
            addAction(ModelDownloadService.ACTION_MODEL_READY)
            addAction(ModelDownloadService.ACTION_MODEL_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        instance = this
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        // Deliver failure to any dangling pending result so Flutter doesn't hang
        pendingProjectionResult?.success(false)
        pendingProjectionResult = null
        instance = null
        super.onDestroy()
    }

    // ── Permission + projection flow ──────────────────────────────────────────

    /**
     * Step 1: check overlay permission, then check RECORD_AUDIO.
     * If audio is already granted → jump straight to media projection.
     * If not → request it; continuation is in onRequestPermissionsResult().
     */
    private fun requestAudioThenProjection(result: MethodChannel.Result) {
        // Guard: overlay must be granted first
        if (!Settings.canDrawOverlays(this)) {
            result.success(false)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaProjection(result)
        } else {
            // Store result — we'll continue in onRequestPermissionsResult()
            deliverPendingFailure()           // clear any stale pending result first
            pendingProjectionResult = result
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO_PERMISSION
            )
        }
    }

    /**
     * Step 2: launch the system screen-capture consent dialog.
     */
    private fun requestMediaProjection(result: MethodChannel.Result) {
        deliverPendingFailure()               // clear any stale pending result first
        pendingProjectionResult = result
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "createScreenCaptureIntent failed: ${e.message}")
            pendingProjectionResult = null
            result.success(false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_AUDIO_PERMISSION) {
            val pending = pendingProjectionResult
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pending != null) {
                    // DO NOT call requestMediaProjection here with the same `pending`:
                    // requestMediaProjection clears pendingProjectionResult itself.
                    pendingProjectionResult = null
                    requestMediaProjection(pending)
                }
            } else {
                pendingProjectionResult = null
                pending?.success(false)
            }
        }
    }

    @Deprecated("Required for API compatibility below 33")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_MEDIA_PROJECTION) {
            val pending = pendingProjectionResult
            pendingProjectionResult = null             // consume before any dispatch

            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "MediaProjection granted — starting SpeechCaptureService")

                // CRITICAL: On API 34+ the MediaProjection token is ONE-USE.
                // We must pass the raw result code + data Intent to the service;
                // the service calls MediaProjectionManager.getMediaProjection() itself.
                val i = Intent(this, SpeechCaptureService::class.java).apply {
                    putExtra(SpeechCaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(SpeechCaptureService.EXTRA_RESULT_DATA, data)
                }
                startForegroundServiceCompat(i)

                // Warm up LibreTranslate connection (fire-and-forget)
                TranslationManager.warmUp()

                pending?.success(true)
            } else {
                Log.w(TAG, "MediaProjection denied or cancelled (resultCode=$resultCode)")
                pending?.success(false)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Start a foreground service using the correct API for the current OS version. */
    private fun startForegroundServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** If there is a dangling pending result, deliver failure and clear it. */
    private fun deliverPendingFailure() {
        val stale = pendingProjectionResult
        if (stale != null) {
            pendingProjectionResult = null
            try { stale.success(false) } catch (_: Exception) {}
        }
    }

    /** Called from SpeechCaptureService to push a translation to Flutter UI. */
    fun onTranslation(original: String, english: String, hindi: String) {
        runOnUiThread {
            methodChannel?.invokeMethod("onTranslation", mapOf(
                "original" to original,
                "english"  to english,
                "hindi"    to hindi
            ))
        }
    }
}
