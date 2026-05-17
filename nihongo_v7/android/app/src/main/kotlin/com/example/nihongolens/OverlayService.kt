package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService
 *
 * Draws a floating subtitle bar (TYPE_APPLICATION_OVERLAY) over all other apps.
 * The bar is transparent — only white bold text with a black shadow is visible,
 * so it can sit over any video without blocking content.
 *
 * Draggable: the user can reposition the subtitles anywhere on screen.
 *
 * Text buffering:
 *  - Up to 2 lines are shown at once.
 *  - After 2 lines are accumulated the bar pauses for READ_PAUSE_MS to let
 *    the viewer read, then rolls in the next batch.
 *  - After CLEAR_DELAY_MS of silence the overlay fades out.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestEnglish  = ""
        @Volatile var latestHindi    = ""

        @Volatile private var pushCallback: ((String, String, String) -> Unit)? = null

        fun updateText(original: String, english: String, hindi: String = "") {
            latestOriginal = original
            latestEnglish  = english
            latestHindi    = hindi
            pushCallback?.invoke(original, english, hindi)
        }
    }

    private var windowManager: WindowManager?           = null
    private var overlayView:   View?                    = null
    private var subtitleTv:    TextView?                = null
    private var params:        WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    // 2-line read-pause buffer
    private val lineBuffer    = ArrayDeque<String>(4)
    private val pendingBuffer = ArrayDeque<String>(16)
    private val MAX_LINES     = 2
    private val READ_PAUSE_MS = 3_500L
    private val CLEAR_DELAY   = 12_000L
    @Volatile private var isPaused       = false
    private var pauseRunnable: Runnable? = null
    private var clearRunnable: Runnable? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }

        pushCallback = { _, english, hindi ->
            mainHandler.post { onNewText(english, hindi) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running      = false
        pushCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Text handling ─────────────────────────────────────────────────────────

    private fun onNewText(english: String, hindi: String) {
        val targetLang = SpeechCaptureService.targetLanguage
        val display = when {
            targetLang == "hindi" && hindi.isNotBlank() -> hindi
            english.isNotBlank()                        -> english
            else                                        -> return
        }

        val lines = display.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (isPaused) {
            lines.forEach { line ->
                if (pendingBuffer.lastOrNull() != line) pendingBuffer.addLast(line)
            }
            rescheduleClear()
            return
        }

        for (line in lines) {
            if (lineBuffer.lastOrNull() == line) continue
            lineBuffer.addLast(line)
            if (lineBuffer.size >= MAX_LINES) {
                showBuffer()
                startPause()
                return
            }
        }
        showBuffer()
        rescheduleClear()
    }

    private fun showBuffer() {
        val text = lineBuffer.joinToString("\n")
        subtitleTv?.apply {
            if (this.text.toString() != text) {
                this.text = text
                alpha = 1f
                clearAnimation()
                startAnimation(AlphaAnimation(0.3f, 1f).apply {
                    duration = 200; fillAfter = true
                })
            }
        }
    }

    private fun startPause() {
        isPaused = true
        pauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pauseRunnable = Runnable {
            lineBuffer.clear()
            while (pendingBuffer.isNotEmpty() && lineBuffer.size < MAX_LINES)
                lineBuffer.addLast(pendingBuffer.removeFirst())
            isPaused = false
            if (lineBuffer.isNotEmpty()) {
                showBuffer()
                if (lineBuffer.size >= MAX_LINES) startPause() else rescheduleClear()
            } else {
                subtitleTv?.text = ""
                rescheduleClear()
            }
        }
        mainHandler.postDelayed(pauseRunnable!!, READ_PAUSE_MS)
    }

    private fun rescheduleClear() {
        clearRunnable?.let { mainHandler.removeCallbacks(it) }
        clearRunnable = Runnable {
            subtitleTv?.startAnimation(AlphaAnimation(1f, 0f).apply {
                duration = 2_000; fillAfter = true
            })
            mainHandler.postDelayed({
                lineBuffer.clear()
                pendingBuffer.clear()
                isPaused = false
                subtitleTv?.apply { clearAnimation(); alpha = 1f; text = "" }
            }, 2_100)
        }
        mainHandler.postDelayed(clearRunnable!!, CLEAR_DELAY)
    }

    // ── Overlay construction ──────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            subtitleTv = TextView(this).apply {
                text     = ""
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setLineSpacing(0f, 1.2f)
                maxLines = MAX_LINES
                gravity  = Gravity.CENTER_HORIZONTAL
                // Multi-layer shadow for readability on any video background
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            container.addView(subtitleTv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))

            overlayView = container

            val sw = resources.displayMetrics.widthPixels
            params = WindowManager.LayoutParams(
                (sw * 0.96).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(80)  // above the navigation bar
            }

            // Make the overlay draggable so the user can reposition it
            var startRawX = 0f; var startRawY = 0f
            var initX = 0;      var initY = 0
            container.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX = p.x;         initY = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Caption Lens Overlay", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Live translation overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
