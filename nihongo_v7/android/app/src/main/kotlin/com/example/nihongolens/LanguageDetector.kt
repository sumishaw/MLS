package com.example.nihongolens

import android.util.Log
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions

/**
 * LanguageDetector
 *
 * On-device language identification using ML Kit Language ID.
 * Works fully offline — the model (~1 MB) is bundled with the ML Kit SDK.
 *
 * A lower confidence threshold (0.35 vs the default 0.5) is used so that
 * short or mixed utterances are still detected rather than returning "und".
 */
object LanguageDetector {

    private const val TAG = "LanguageDetector"

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(0.35f)
            .build()
    )

    /**
     * Asynchronously identify the primary language of [text].
     * [onDetected] is called with an ISO 639-1 code (e.g. "en", "ja", "hi")
     * or "und" if the language cannot be determined with sufficient confidence.
     */
    fun detectLanguage(text: String, onDetected: (String) -> Unit) {
        if (text.isBlank()) { onDetected("und"); return }

        identifier.identifyLanguage(text)
            .addOnSuccessListener { code ->
                val result = if (code.isNullOrBlank() || code == "und") "und" else code
                Log.d(TAG, "Detected '$result' for: '${text.take(30)}'")
                onDetected(result)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Detection failed: ${e.message}")
                onDetected("und")
            }
    }

    fun close() = runCatching { identifier.close() }
}
