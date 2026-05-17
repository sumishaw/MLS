package com.example.nihongolens

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TranslationManager — PERFORMANCE-FIXED
 *
 * BUG A — Unbounded executor queue floods LibreTranslate
 *   Original: Executors.newFixedThreadPool(3) uses an unbounded LinkedBlockingQueue.
 *   Vosk partials at 15-30/s × 2 HTTP calls each = 60 pending tasks/second piling up.
 *   LibreTranslate (a Python process on this same device) gets flooded; its own thread
 *   pool maxes out; requests timeout; eventually the nohup process stalls or the OS
 *   OOM-kills it because it holds large language model weights in RAM.
 *   FIX: Bounded queue of 6 slots with DiscardPolicy. Excess tasks are dropped rather
 *   than queued — the debounce in SpeechCaptureService ensures a fresh text arrives
 *   soon anyway. An AtomicInteger tracks in-flight count for an O(1) pre-submit check.
 *
 * BUG B — Executor never shut down; stale tasks accumulate across sessions
 *   TranslationManager is a Kotlin object (singleton). The executor is never terminated
 *   between capture sessions. Tasks from the previous (crashed) session linger in the
 *   queue and execute against the new session's results, producing garbled output.
 *   FIX: closeAll() now drains the queue (purge()) and resets the in-flight counter.
 *   The executor itself is NOT shut down (workers would need to be recreated) but the
 *   queue is emptied so stale tasks cannot execute.
 *
 * BUG C — 15 s timeout per call
 *   For a localhost call this is absurd. A backed-up queue held worker threads blocked
 *   for 15 s each before detecting that LibreTranslate was unreachable.
 *   FIX: 8 s connect/read timeout.
 *
 * BUG D — Cache grows unbounded (memory leak on long sessions)
 *   FIX: Evict the oldest 25 % when the cache reaches MAX_CACHE_ENTRIES.
 *
 * BUG E — Missing Connection: keep-alive
 *   Each call opened a new TCP connection to localhost, adding ~3 ms of TCP handshake
 *   overhead per call and creating unnecessary socket churn.
 *   FIX: Added "Connection: keep-alive" header.
 */
object TranslationManager {

    private const val TAG            = "TranslationManager"
    private const val LIBRE_URL      = "http://localhost:5000"
    private const val API_KEY        = ""
    // BUG C FIX: 8 s is ample for a localhost call
    private const val TIMEOUT_MS     = 8_000
    // BUG D FIX: bounded cache
    private const val MAX_CACHE_ENTRIES = 512

    private val cache    = ConcurrentHashMap<String, String>(256)
    private val inFlight = AtomicInteger(0)

    // BUG A FIX: 2 core threads + bounded queue of 6 + discard-when-full
    private const val MAX_QUEUE = 6
    private val executor = ThreadPoolExecutor(
        2, 2,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(MAX_QUEUE),
        { r -> Thread(r, "LibreTranslateWorker").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy()   // silently drop when queue is full
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun translate(
        text: String,
        sourceLang: String,
        targetLang: String,
        onTranslated: (String) -> Unit
    ) {
        if (text.isBlank()) { onTranslated(text); return }

        val src = normaliseLang(sourceLang)
        val tgt = normaliseLang(targetLang)
        if (src == tgt) { onTranslated(text); return }

        val cacheKey = "$src|$tgt|$text"
        cache[cacheKey]?.let { onTranslated(it); return }

        // BUG A FIX: drop immediately if we're already saturated
        if (inFlight.get() >= MAX_QUEUE) {
            Log.w(TAG, "Saturated — dropping: ${text.take(30)}")
            onTranslated(text)   // return original so UI stays responsive
            return
        }

        inFlight.incrementAndGet()
        executor.submit {
            try {
                val result = callLibreTranslate(text, src, tgt)
                val output = result ?: text
                if (result != null) {
                    // BUG D FIX: evict oldest entries when cache is large
                    if (cache.size >= MAX_CACHE_ENTRIES) {
                        cache.keys.take(MAX_CACHE_ENTRIES / 4).forEach { cache.remove(it) }
                    }
                    cache[cacheKey] = result
                }
                onTranslated(output)
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    fun warmUp() {
        executor.submit {
            try {
                callLibreTranslate("hello", "en", "hi")
                Log.d(TAG, "LibreTranslate warm-up OK")
            } catch (e: Exception) {
                Log.w(TAG, "Warm-up failed (normal if not yet started): ${e.message}")
            }
        }
    }

    /** BUG B FIX: drain the queue so stale tasks from a crashed session don't execute. */
    fun closeAll() {
        executor.queue.clear()   // purge pending tasks
        inFlight.set(0)          // reset counter (in-flight tasks finish naturally)
        cache.clear()
        Log.d(TAG, "Cache cleared, queue drained")
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private fun callLibreTranslate(text: String, src: String, tgt: String): String? {
        return try {
            val conn = URL("$LIBRE_URL/translate").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            // BUG E FIX: reuse TCP connection to localhost
            conn.setRequestProperty("Connection", "keep-alive")
            conn.doOutput       = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS

            val body = JSONObject().apply {
                put("q", text)
                put("source", src)
                put("target", tgt)
                put("format", "text")
                if (API_KEY.isNotBlank()) put("api_key", API_KEY)
            }.toString()

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

            val respCode = conn.responseCode
            if (respCode != 200) {
                Log.w(TAG, "LibreTranslate HTTP $respCode for $src→$tgt")
                return null
            }

            val json       = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).readText())
            val translated = json.optString("translatedText", "").trim()

            if (translated.isBlank()) null
            else translated.also { Log.d(TAG, "Translated $src→$tgt: ${it.take(60)}") }

        } catch (e: Exception) {
            Log.e(TAG, "LibreTranslate $src→$tgt error: ${e.message}")
            null
        }
    }

    // ── Language code normalisation ───────────────────────────────────────────

    private fun normaliseLang(code: String): String = when (code.lowercase().trim()) {
        "en", "und", ""                  -> "en"
        "hi"                             -> "hi"
        "ja"                             -> "ja"
        "ko"                             -> "ko"
        "zh", "zh-cn", "zh-tw", "zh-hk" -> "zh"
        "fr"                             -> "fr"
        "es"                             -> "es"
        "de"                             -> "de"
        "tr"                             -> "tr"
        "it"                             -> "it"
        "pt"                             -> "pt"
        "ar"                             -> "ar"
        "ru"                             -> "ru"
        "nl"                             -> "nl"
        "pl"                             -> "pl"
        "sv"                             -> "sv"
        "id"                             -> "id"
        "vi"                             -> "vi"
        "th"                             -> "th"
        else                             -> "en"
    }
}
