package com.waz.hinditap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Two translation backends:
 *
 *  ONLINE  — Google's public translate endpoint. Much better phrasing and word
 *            order, because the model behind it is far larger than anything that
 *            fits on a phone. Needs internet, and the text does leave the device.
 *
 *  OFFLINE — ML Kit's on-device model (~30 MB). Always available, private, but
 *            noticeably weaker on idiomatic sentences and headline-style text.
 *
 * Default is online-with-offline-fallback. The user can force offline in settings.
 */
object TranslationEngine {

    private val pool = Executors.newFixedThreadPool(2)

    private const val PREFS = "hinditap"
    private const val KEY_OFFLINE = "force_offline"
    private const val KEY_GEMINI = "gemini_key"

    /** Optional Gemini API key from Google AI Studio. Best quality when present. */
    @Volatile
    var geminiKey: String = ""

    /** When true the online backend is never used. Persisted across launches. */
    @Volatile
    var forceOffline: Boolean = false

    /** Entry points call this so the preference applies even without MainActivity. */
    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        forceOffline = prefs.getBoolean(KEY_OFFLINE, false)
        geminiKey = prefs.getString(KEY_GEMINI, "").orEmpty().trim()
    }

    fun setGeminiKey(context: Context, key: String) {
        geminiKey = key.trim()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GEMINI, geminiKey)
            .apply()
    }

    fun setForceOffline(context: Context, value: Boolean) {
        forceOffline = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE, value)
            .apply()
    }

    fun translateToHindi(
        text: String,
        context: Context? = null,
        onStatus: (String) -> Unit = {},
        onError: (String) -> Unit,
        onResult: (String, String) -> Unit
    ) {
        val input = normalize(text)
        if (input.isEmpty()) {
            onError("Koi text nahi mila.")
            return
        }

        val canTryOnline = !forceOffline && (context == null || hasInternet(context))

        if (canTryOnline) {
            onStatus("Translating…")
            pool.execute {
                // Best first: Gemini understands that a headline is a headline.
                if (geminiKey.isNotEmpty()) {
                    val g = runCatching { translateWithGemini(input) }.getOrNull()
                    if (!g.isNullOrBlank()) {
                        onResult(g, "gemini")
                        return@execute
                    }
                }

                val online = runCatching { translateOnline(input) }.getOrNull()
                if (!online.isNullOrBlank()) {
                    onResult(online, "online")
                } else {
                    // Network hiccup, rate limit, endpoint change — fall back silently.
                    translateOffline(input, onStatus, onError, onResult)
                }
            }
        } else {
            translateOffline(input, onStatus, onError, onResult)
        }
    }

    /**
     * Forces the ML Kit path so the offline model actually downloads — the normal
     * entry point would go online and never touch it.
     */
    fun downloadOfflineModel(
        onStatus: (String) -> Unit = {},
        onError: (String) -> Unit,
        onResult: (String, String) -> Unit
    ) = translateOffline("Hello, this is a test.", onStatus, onError, onResult)

    // ---------------------------------------------------------------- normalize

    /**
     * Newspaper headlines arrive as "Tear Gas Fired To Disperse Angry Crowd".
     * Translation models are trained mostly on ordinary sentence case, and
     * title case measurably degrades their output, so flatten it first.
     * Acronyms (CM, NSCN) are left alone.
     */
    private fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""

        val words = text.split(" ")
        if (words.size < 3) return text

        val titleCased = words.count { w ->
            w.length > 1 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() }
        }

        // Mostly Title Case? Lower it, but keep all-caps tokens (likely acronyms).
        return if (titleCased >= words.size * 0.6) {
            words.joinToString(" ") { w ->
                if (w.length > 1 && w.all { !it.isLetter() || it.isUpperCase() }) w
                else w.lowercase()
            }.replaceFirstChar { it.uppercase() }
        } else {
            text
        }
    }

    // ---------------------------------------------------------------- gemini

    /**
     * Gemini handles what plain translation APIs cannot: it knows the text is a
     * newspaper fragment, keeps proper nouns intact, and produces natural Hindi
     * instead of a word-by-word mapping. OCR noise gets cleaned up too.
     */
    private fun translateWithGemini(text: String): String? {
        val prompt = """
            Translate the following text into natural, fluent Hindi.
            It is taken from an English newspaper, so it may be a headline or a
            partial sentence, and may contain small OCR errors — silently correct
            obvious mistakes.

            Rules:
            - Output ONLY the Hindi translation. No notes, no transliteration,
              no quotes, no explanation.
            - Keep names of people, places and organisations recognisable.
            - Headlines should read like natural Hindi newspaper headlines.

            Text:
            $text
        """.trimIndent()

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(
                    JSONObject().put("text", prompt)
                ))
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 1024)
            })
        }.toString()

        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-flash-latest:generateContent?key=$geminiKey"
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) return null

            val response = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                .use { it.readText() }

            JSONObject(response)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .ifEmpty { null }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- online

    private fun translateOnline(text: String): String? {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=hi&dt=t&q=" +
                URLEncoder.encode(text, "UTF-8")
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        return try {
            if (conn.responseCode != 200) return null

            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                .use { it.readText() }

            // Shape: [[["translated","source",...],["more","more",...]], ...]
            val segments = JSONArray(body).getJSONArray(0)
            buildString {
                for (i in 0 until segments.length()) {
                    append(segments.getJSONArray(i).getString(0))
                }
            }.trim().ifEmpty { null }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------------------------------------------------------- offline

    private fun translateOffline(
        input: String,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
        onResult: (String, String) -> Unit
    ) {
        val identifier = LanguageIdentification.getClient()
        identifier.identifyLanguage(input)
            .addOnSuccessListener { code ->
                val source = if (code == "und") TranslateLanguage.ENGLISH
                else TranslateLanguage.fromLanguageTag(code) ?: TranslateLanguage.ENGLISH

                if (source == TranslateLanguage.HINDI) {
                    onResult(input, "hi")
                    return@addOnSuccessListener
                }
                runMlKit(input, source, onStatus, onError, onResult)
            }
            .addOnFailureListener {
                runMlKit(input, TranslateLanguage.ENGLISH, onStatus, onError, onResult)
            }
    }

    private fun runMlKit(
        input: String,
        sourceLang: String,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit,
        onResult: (String, String) -> Unit
    ) {
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(TranslateLanguage.HINDI)
                .build()
        )

        onStatus("Translating (offline)…")

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translator.translate(input)
                    .addOnSuccessListener { translated ->
                        onResult(translated, "offline")
                        translator.close()
                    }
                    .addOnFailureListener { e ->
                        onError("Translation fail: ${e.localizedMessage}")
                        translator.close()
                    }
            }
            .addOnFailureListener { e ->
                onError(
                    "Offline model nahi mila. Internet on karke app mein ek baar download karein.\n(${e.localizedMessage})"
                )
                translator.close()
            }
    }
}
