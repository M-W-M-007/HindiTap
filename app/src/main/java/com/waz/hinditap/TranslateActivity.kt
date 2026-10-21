package com.waz.hinditap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.waz.hinditap.databinding.ActivityTranslateBinding

/**
 * Launched from the text-selection toolbar (PROCESS_TEXT) or the share sheet (SEND).
 * Shows as a small dialog over whatever app the user is already in — the PDF
 * stays on screen behind it.
 */
class TranslateActivity : AppCompatActivity() {

    companion object {
        /** Sent by the Quick Settings tile: translate whatever is on the clipboard. */
        const val ACTION_TRANSLATE_CLIPBOARD = "com.waz.hinditap.TRANSLATE_CLIPBOARD"
    }

    private lateinit var binding: ActivityTranslateBinding
    private var translatedText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranslateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val selected = extractText(intent)

        if (selected.isNullOrBlank()) {
            val hint = if (intent.action == ACTION_TRANSLATE_CLIPBOARD)
                "Clipboard khali hai. Pehle text select karke Copy karein."
            else
                "Koi text nahi mila."
            Toast.makeText(this, hint, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.sourceText.text = selected
        binding.statusText.text = "Translating…"
        binding.resultText.visibility = View.GONE
        binding.copyButton.visibility = View.GONE
        binding.speakEnButton.visibility = View.GONE
        binding.speakHiButton.visibility = View.GONE

        TranslationEngine.loadPrefs(this)

        TranslationEngine.translateToHindi(
            text = selected,
            context = this,
            onStatus = { msg -> runOnUiThread { binding.statusText.text = msg } },
            onError = { msg ->
                runOnUiThread {
                    binding.statusText.text = msg
                    binding.progress.visibility = View.GONE
                }
            },
            onResult = { hindi, detected ->
                runOnUiThread {
                    translatedText = hindi
                    binding.progress.visibility = View.GONE
                    binding.statusText.text = "$detected → हिंदी"
                    binding.resultText.text = hindi
                    binding.resultText.visibility = View.VISIBLE
                    binding.copyButton.visibility = View.VISIBLE
                    binding.speakEnButton.visibility = View.VISIBLE
                    binding.speakHiButton.visibility = View.VISIBLE
                }
            }
        )

        binding.copyButton.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Hindi", translatedText))
            Toast.makeText(this, "Copy ho gaya", Toast.LENGTH_SHORT).show()
        }

        TtsHelper.init(this)

        binding.speakEnButton.setOnClickListener {
            TtsHelper.speak(this, selected, TtsHelper.ENGLISH)
                ?.let { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }

        binding.speakHiButton.setOnClickListener {
            TtsHelper.speak(this, translatedText, TtsHelper.HINDI)
                ?.let { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }

        binding.closeButton.setOnClickListener {
            TtsHelper.stop()
            finish()
        }
    }

    /** Handles the selection toolbar, the share sheet, and the Quick Settings tile. */
    private fun extractText(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_PROCESS_TEXT ->
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT_READONLY)?.toString()

        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)

        ACTION_TRANSLATE_CLIPBOARD -> readClipboard()

        else -> null
    }

    /**
     * Clipboard reads are only permitted while this activity is in the foreground,
     * which is the case by the time the tile has launched it.
     */
    private fun readClipboard(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }
}
