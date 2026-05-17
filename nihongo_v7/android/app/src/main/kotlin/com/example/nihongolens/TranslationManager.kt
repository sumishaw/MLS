package com.example.nihongolens

import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * TranslationManager
 *
 * Sends translation requests to the LibreTranslate instance already running
 * on this tablet at http://localhost:5000/translate.
 *
 * No internet required — completely local.
 * Thread pool of 3 so English and Hindi translations can run concurrently.
 * Simple in-memory LRU-like cache (ConcurrentHashMap, cleared on service stop).
 */
object TranslationManager {

    private const val TAG        = "TranslationManager"
    private const val LIBRE_URL  = "http://localhost:5000"
    private const val API_KEY    = ""               // set if your LibreTranslate needs a key
    private const val TIMEOUT_MS = 15_000           // 15 s per translation call

    private val cache    = ConcurrentHashMap<String, String>(256)
    private val executor = Executors.newFixedThreadPool(3)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Translate [text] from [sourceLang] to [targetLang] (ISO 639-1 codes, e.g. "en", "hi").
     * [onTranslated] is always called (on an executor thread), even on failure (returns original text).
     */
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

        executor.submit {
            val result = callLibreTranslate(text, src, tgt)
            val output = result ?: text          // fall back to original on error
            if (result != null) cache[cacheKey] = result
            onTranslated(output)
        }
    }

    /**
     * Fire a cheap warm-up request so LibreTranslate's language models are
     * loaded before the first real translation arrives.
     */
    fun warmUp() {
        executor.submit {
            try {
                callLibreTranslate("hello", "en", "hi")
                Log.d(TAG, "LibreTranslate warm-up complete")
            } catch (e: Exception) {
                Log.w(TAG, "LibreTranslate warm-up failed (normal if not yet started): ${e.message}")
            }
        }
    }

    /** Clear cache (called when SpeechCaptureService stops). */
    fun closeAll() {
        cache.clear()
        Log.d(TAG, "Cache cleared")
    }

    // ── HTTP call ─────────────────────────────────────────────────────────────

    private fun callLibreTranslate(text: String, src: String, tgt: String): String? {
        return try {
            val conn = URL("$LIBRE_URL/translate").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
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
            else translated.also {
                Log.d(TAG, "Translated $src→$tgt: ${it.take(60)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "LibreTranslate $src→$tgt error: ${e.message}")
            null
        }
    }

    // ── Language code normalisation ───────────────────────────────────────────

    /** Map any recognised language code to the ISO 639-1 code LibreTranslate expects. */
    private fun normaliseLang(code: String): String = when (code.lowercase().trim()) {
        "en", "und", ""                 -> "en"
        "hi"                            -> "hi"
        "ja"                            -> "ja"
        "ko"                            -> "ko"
        "zh", "zh-cn", "zh-tw", "zh-hk" -> "zh"
        "fr"                            -> "fr"
        "es"                            -> "es"
        "de"                            -> "de"
        "tr"                            -> "tr"
        "it"                            -> "it"
        "pt"                            -> "pt"
        "ar"                            -> "ar"
        "ru"                            -> "ru"
        "nl"                            -> "nl"
        "pl"                            -> "pl"
        "sv"                            -> "sv"
        "id"                            -> "id"
        "vi"                            -> "vi"
        "th"                            -> "th"
        else                            -> "en"  // unknown → treat as English
    }
}
