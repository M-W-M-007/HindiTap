package com.waz.hinditap

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.waz.hinditap.databinding.ActivityMainBinding

/**
 * Setup screen. Three separate capabilities, because no single one covers
 * every app:
 *
 *  1. Selection menu ("हिंदी" in the Copy/Share popup) — browsers, notes, chat.
 *  2. Clipboard / Quick Settings tile — PDF readers with their own toolbar.
 *  3. Screen-capture OCR — image-based PDFs (e-papers) with no text at all.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastClipTranslated: String? = null

    /** Screen-capture consent. Granted per session; the service holds it after that. */
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val svc = Intent(this, ScreenTranslateService::class.java).apply {
                action = ScreenTranslateService.ACTION_START
                putExtra(ScreenTranslateService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenTranslateService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(svc)

            Toast.makeText(
                this,
                "Bubble chalu. E-paper kholein aur bubble tap karein.",
                Toast.LENGTH_LONG
            ).show()

            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Screen capture permission nahi mili.", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Denying it only costs the "stop" notification action. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        TranslationEngine.loadPrefs(this)

        binding.offlineSwitch.isChecked = TranslationEngine.forceOffline
        binding.offlineSwitch.setOnCheckedChangeListener { _, checked ->
            TranslationEngine.setForceOffline(this, checked)
        }

        binding.geminiKeyInput.setText(TranslationEngine.geminiKey)
        binding.saveKeyButton.setOnClickListener {
            val key = binding.geminiKeyInput.text.toString().trim()
            TranslationEngine.setGeminiKey(this, key)

            binding.keyStatus.visibility = View.VISIBLE

            if (key.isEmpty()) {
                binding.keyStatus.text = "Key hata di. Ab public endpoint use hoga."
                return@setOnClickListener
            }

            binding.keyStatus.text = "Key test ho rahi hai…"
            TranslationEngine.translateToHindi(
                text = "Tear gas fired to disperse angry crowd.",
                context = this,
                onError = { msg -> runOnUiThread { binding.keyStatus.text = msg } },
                onResult = { hindi, engine ->
                    runOnUiThread {
                        binding.keyStatus.text = if (engine == "gemini")
                            "Key chal rahi hai ✓\n\nTest: $hindi"
                        else
                            "Key kaam nahi kari — app public endpoint par chali gayi. " +
                                "Key dobara check karein (AI Studio se copy, bina space ke).\n\nTest: $hindi"
                    }
                }
            )
        }

        binding.downloadButton.setOnClickListener {
            binding.downloadStatus.visibility = View.VISIBLE
            binding.downloadStatus.text = "Model download ho raha hai… (~30 MB, ek hi baar)"

            TranslationEngine.downloadOfflineModel(
                onError = { msg -> runOnUiThread { binding.downloadStatus.text = msg } },
                onResult = { _, _ ->
                    runOnUiThread {
                        binding.downloadStatus.text = "Ready. Ab offline bhi chalega."
                    }
                }
            )
        }

        binding.bubbleButton.setOnClickListener { startScreenReader() }

        binding.stopButton.setOnClickListener {
            startService(
                Intent(this, ScreenTranslateService::class.java)
                    .setAction(ScreenTranslateService.ACTION_STOP)
            )
            Toast.makeText(this, "Bubble band.", Toast.LENGTH_SHORT).show()
        }

        binding.translateButton.setOnClickListener {
            val input = binding.inputText.text.toString()
            binding.pasteResult.text = "Translating…"

            TranslationEngine.translateToHindi(
                text = input,
                context = this,
                onError = { msg -> runOnUiThread { binding.pasteResult.text = msg } },
                onResult = { hindi, _ -> runOnUiThread { binding.pasteResult.text = hindi } }
            )
        }
    }

    /**
     * Overlay permission lives in a system settings screen, so it has to be
     * granted before the capture prompt makes sense.
     */
    private fun startScreenReader() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Pehle \"Display over other apps\" allow karein, phir wapas aakar yeh button dobara dabayein.",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun onResume() {
        super.onResume()
        binding.bubbleStatus.text = if (ScreenTranslateService.isRunning)
            "Screen reader: ON" else "Screen reader: OFF"
        translateClipboard()
    }

    /** Clipboard reads are allowed because this activity is in the foreground. */
    private fun translateClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text == lastClipTranslated) return

        lastClipTranslated = text

        binding.inputText.setText(text)
        binding.clipLabel.visibility = View.VISIBLE
        binding.pasteResult.text = "Translating…"

        TranslationEngine.translateToHindi(
            text = text,
            context = this,
            onError = { msg -> runOnUiThread { binding.pasteResult.text = msg } },
            onResult = { hindi, _ -> runOnUiThread { binding.pasteResult.text = hindi } }
        )
    }
}
