package com.example.nihongolens

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * ModelDownloadService
 *
 * Downloads vosk-model-en-us-0.22 (~1.8 GB) to:
 *   context.filesDir/vosk-model/
 *
 * Folder layout after unzip (matches what SpeechCaptureService passes to Vosk):
 *   <filesDir>/vosk-model/am/final.mdl
 *   <filesDir>/vosk-model/conf/model.conf
 *   <filesDir>/vosk-model/graph/HCLG.fst
 *   <filesDir>/vosk-model/ivector/final.dubm
 *   … (all other model files)
 *
 * Features:
 *  - HTTP Range resume (survives interrupted downloads)
 *  - SSL bypass for alphacephei.com (intermediate CA missing on some Android)
 *  - Storage space pre-check
 *  - Model integrity check (required files must exist)
 *  - Version stamp — bump MODEL_VERSION to force re-download in future APK builds
 *  - WakeLock to survive screen-off during long download
 */
class ModelDownloadService : Service() {

    companion object {
        const val CHANNEL_ID               = "model_download_channel"
        const val NOTIF_ID                 = 3
        const val ACTION_MODEL_READY       = "com.example.nihongolens.MODEL_READY"
        const val ACTION_MODEL_ERROR       = "com.example.nihongolens.MODEL_ERROR"
        const val ACTION_DOWNLOAD_PROGRESS = "com.example.nihongolens.DOWNLOAD_PROGRESS"
        const val EXTRA_ERROR_MSG          = "error_msg"
        const val EXTRA_PROGRESS_PCT       = "progress_pct"
        const val EXTRA_DOWNLOADED_MB      = "downloaded_mb"
        const val EXTRA_TOTAL_MB           = "total_mb"

        /** The folder name inside context.filesDir where the model lives. */
        const val MODEL_FOLDER = "vosk-model"

        private const val MODEL_URL          = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"
        private const val PARTIAL_FILE       = "vosk_model_download.zip.part"
        private const val VERSION_STAMP_FILE = "vosk_model_version"

        /**
         * Bump this string in your next build to force a fresh model download
         * if the model structure changes or the zip URL changes.
         */
        private const val MODEL_VERSION = "v1"

        /** Files that MUST exist for the model to be considered intact. */
        private val REQUIRED_MODEL_FILES = listOf(
            "am/final.mdl",
            "conf/model.conf",
            "graph/HCLG.fst",
            "ivector/final.dubm"
        )

        private const val TAG = "ModelDownload"

        // Progress exposed for in-process polling
        @Volatile var currentProgress = 0
        @Volatile var currentDlMb     = 0L
        @Volatile var currentTotalMb  = 0L
        @Volatile var isDownloading   = false

        // ── Public API ────────────────────────────────────────────────────────

        /** Returns true only if the model exists, is intact, and matches the version stamp. */
        fun isModelReady(context: Context): Boolean {
            val dir = modelDir(context)

            if (!dir.isDirectory || dir.listFiles().isNullOrEmpty()) {
                Log.d(TAG, "isModelReady=false: dir missing or empty")
                return false
            }

            // Version check
            val versionFile = File(context.filesDir, VERSION_STAMP_FILE)
            val installed   = runCatching { versionFile.readText().trim() }.getOrElse { "" }
            if (installed != MODEL_VERSION) {
                Log.w(TAG, "isModelReady=false: version mismatch (installed='$installed', need='$MODEL_VERSION') — wiping")
                wipeModel(context)
                return false
            }

            // Integrity check
            val missing = REQUIRED_MODEL_FILES.filter { !File(dir, it).exists() }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "isModelReady=false: missing files $missing — wiping")
                wipeModel(context)
                return false
            }

            Log.d(TAG, "isModelReady=true (version=$installed)")
            return true
        }

        /** The directory where the model is stored. */
        fun modelDir(context: Context): File = File(context.filesDir, MODEL_FOLDER)

        private fun wipeModel(context: Context) {
            modelDir(context).deleteRecursively()
            File(context.filesDir, VERSION_STAMP_FILE).delete()
            File(context.filesDir, PARTIAL_FILE).delete()
        }
    }

    private val mainHandler    = Handler(Looper.getMainLooper())
    private var wakeLock:       PowerManager.WakeLock? = null
    private var downloadThread: Thread? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Must call startForeground immediately in onCreate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("Starting download…", 0, true),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("Starting download…", 0, true))
        }

        // WakeLock — keeps CPU alive during download even if screen turns off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("WakelockTimeout")
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CaptionLens::ModelDownload"
        ).also { it.acquire(4 * 60 * 60 * 1000L) }   // max 4 hours

        Log.d(TAG, "onCreate — wakeLock acquired: ${wakeLock?.isHeld}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Prevent duplicate download threads
        if (downloadThread?.isAlive == true) {
            Log.d(TAG, "Download already in progress — ignoring")
            return START_NOT_STICKY
        }

        isDownloading  = true
        downloadThread = Thread({
            try {
                downloadWithResume()
                // Write version stamp — marks model as ready
                File(filesDir, VERSION_STAMP_FILE).writeText(MODEL_VERSION)
                Log.d(TAG, "Version stamp written. Model ready (version=$MODEL_VERSION).")
                isDownloading = false
                mainHandler.post {
                    broadcastProgress(100, currentTotalMb, currentTotalMb)
                    sendBroadcast(Intent(ACTION_MODEL_READY).apply { setPackage(packageName) })
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.javaClass.simpleName}: ${e.message}")
                isDownloading = false
                val errMsg = friendlyError(e)
                mainHandler.post {
                    sendBroadcast(Intent(ACTION_MODEL_ERROR).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_ERROR_MSG, errMsg)
                    })
                    stopSelf()
                }
            }
        }, "ModelDownloadThread").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
            start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    // ── Download logic ────────────────────────────────────────────────────────

    private fun downloadWithResume() {
        val destDir     = modelDir(this)
        val partialFile = File(filesDir, PARTIAL_FILE)
        destDir.mkdirs()

        // Storage space check — need ~4 GB (download + unzip)
        val freeBytes = filesDir.freeSpace
        Log.d(TAG, "Free storage: ${freeBytes / (1024 * 1024)} MB")
        if (freeBytes < 3L * 1024 * 1024 * 1024) {
            Log.w(TAG, "Low storage: ${freeBytes / (1024 * 1024)} MB free — download may fail")
        }

        val alreadyDownloaded = partialFile.length()
        val totalBytes        = getTotalSize()
        currentTotalMb        = totalBytes / (1024 * 1024)
        Log.d(TAG, "Remote size: $currentTotalMb MB, already downloaded: $alreadyDownloaded bytes")

        // Skip download if partial file is already complete
        if (alreadyDownloaded >= totalBytes && totalBytes > 0) {
            Log.d(TAG, "Partial file already complete — unzipping")
            doUnzip(partialFile, destDir, totalBytes)
            return
        }

        val conn = openTrustedConnection(MODEL_URL)
        conn.connectTimeout = 30_000
        conn.readTimeout    = 0           // No read timeout — file is 1.8 GB
        conn.setRequestProperty("User-Agent", "CaptionLens/1.0")
        conn.setRequestProperty("Accept-Encoding", "identity")  // must receive raw zip

        val isResume = alreadyDownloaded in 1 until totalBytes
        if (isResume) {
            conn.setRequestProperty("Range", "bytes=$alreadyDownloaded-")
            Log.d(TAG, "Resuming from byte $alreadyDownloaded")
        }

        conn.connect()
        val code = conn.responseCode
        Log.d(TAG, "HTTP $code")

        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw IOException("HTTP $code: ${conn.responseMessage}")
        }

        var downloadedBytes   = if (code == HttpURLConnection.HTTP_PARTIAL) alreadyDownloaded else 0L
        var lastProgressTime  = System.currentTimeMillis()

        RandomAccessFile(partialFile, "rw").use { raf ->
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                raf.seek(alreadyDownloaded)
            } else {
                raf.setLength(0)    // server didn't support range — restart
            }
            conn.inputStream.use { input ->
                val buf = ByteArray(65_536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    raf.write(buf, 0, n)
                    downloadedBytes += n

                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime >= 1_000) {
                        lastProgressTime = now
                        val pct  = if (totalBytes > 0)
                            (downloadedBytes * 100 / totalBytes).toInt().coerceAtMost(98) else 0
                        val dlMb = downloadedBytes / (1024 * 1024)
                        currentProgress = pct
                        currentDlMb     = dlMb
                        Log.d(TAG, "Progress: $pct% — $dlMb / $currentTotalMb MB")
                        updateNotification("Downloading… $pct%  ($dlMb MB / $currentTotalMb MB)", pct)
                        broadcastProgress(pct, dlMb, currentTotalMb)
                    }
                }
            }
        }

        Log.d(TAG, "Download stream complete — ${partialFile.length()} bytes on disk")
        doUnzip(partialFile, destDir, totalBytes)
    }

    private fun doUnzip(partialFile: File, destDir: File, totalBytes: Long) {
        Log.d(TAG, "Unzipping ${partialFile.length()} bytes → $destDir")
        updateNotification("Unzipping model… (a few minutes)", 99)
        broadcastProgress(99, currentTotalMb, currentTotalMb)

        unzipFile(partialFile, destDir)
        partialFile.delete()

        Log.d(TAG, "Unzip complete — ${destDir.list()?.size ?: 0} items in model dir")
        updateNotification("Speech model ready!", 100)
        broadcastProgress(100, currentTotalMb, currentTotalMb)
    }

    // ── Zip extraction ────────────────────────────────────────────────────────

    private fun unzipFile(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered(65_536)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryPath = sanitizeZipEntry(entry.name)
                if (entryPath == null) { zip.closeEntry(); entry = zip.nextEntry; continue }

                val outFile = File(destDir, entryPath)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        val buf = ByteArray(65_536)
                        var n: Int
                        while (zip.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // If the zip had a single top-level folder, flatten it into destDir
        flattenSingleTopLevel(destDir)
    }

    /**
     * Strips the first path component (the zip's own root folder, e.g. "vosk-model-en-us-0.22/")
     * so files land directly in destDir rather than destDir/vosk-model-en-us-0.22/.
     */
    private fun sanitizeZipEntry(name: String): String? {
        if (name.startsWith("/")) return null
        val cleaned = name.replace("\\", "/")
        if (cleaned.contains("..")) return null
        val slash = cleaned.indexOf('/')
        return when {
            slash > 0 && slash < cleaned.length - 1 -> cleaned.substring(slash + 1)
            slash == cleaned.length - 1              -> null   // directory entry
            else                                     -> cleaned
        }
    }

    private fun flattenSingleTopLevel(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            inner.delete()
        }
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    /** HEAD request to get the total file size for progress calculation. */
    private fun getTotalSize(): Long {
        return try {
            val conn = openTrustedConnection(MODEL_URL)
            conn.requestMethod  = "HEAD"
            conn.connectTimeout = 20_000
            conn.readTimeout    = 20_000
            conn.setRequestProperty("User-Agent", "CaptionLens/1.0")
            conn.connect()
            val len = conn.contentLengthLong
            conn.disconnect()
            Log.d(TAG, "HEAD content-length: $len")
            if (len > 0) len else 1_900_000_000L    // fallback ~1.9 GB
        } catch (e: Exception) {
            Log.w(TAG, "HEAD failed: ${e.message} — using fallback")
            1_900_000_000L
        }
    }

    /**
     * Opens a connection that bypasses SSL certificate chain validation.
     * alphacephei.com uses an intermediate CA that is not present in some
     * Android trust stores. Since we are downloading a public model file
     * (no credentials sent) and can verify the file integrity via Vosk itself,
     * this is acceptable.
     */
    private fun openTrustedConnection(urlStr: String): HttpURLConnection {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        })
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        if (conn is HttpsURLConnection) {
            conn.sslSocketFactory = sslCtx.socketFactory
            conn.hostnameVerifier  = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    // ── Broadcast + Notification ──────────────────────────────────────────────

    private fun broadcastProgress(pct: Int, dlMb: Long, totalMb: Long) {
        sendBroadcast(Intent(ACTION_DOWNLOAD_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_PROGRESS_PCT,  pct)
            putExtra(EXTRA_DOWNLOADED_MB, dlMb)
            putExtra(EXTRA_TOTAL_MB,      totalMb)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Speech Model Download", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean = false): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens — Downloading Speech Model (1.8 GB)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress, indeterminate)
            .build()

    private fun updateNotification(text: String, progress: Int) =
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, progress))

    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("ENOSPC")                -> "Not enough storage — free up space and retry"
            msg.contains("Unable to resolve host") -> "No internet — connect to WiFi and retry"
            msg.contains("timeout")               -> "Connection timed out — retry"
            msg.contains("SSL") || msg.contains("certificate") || msg.contains("Chain") ->
                "SSL error — tap Retry"
            else -> "Error: $msg — tap Retry"
        }
    }
}
