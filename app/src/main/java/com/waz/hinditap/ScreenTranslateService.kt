package com.waz.hinditap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/**
 * The piece that makes image-based PDFs (TOI / HT e-papers) work.
 *
 * Flow: floating bubble -> user drags a box over a paragraph -> that screen region
 * is captured -> ML Kit OCR reads the text -> ML Kit translates it to Hindi ->
 * result shows in an overlay panel. The e-paper never closes.
 *
 * Uses MediaProjection (screen capture) + SYSTEM_ALERT_WINDOW (overlay).
 * Deliberately NOT an AccessibilityService.
 */
class ScreenTranslateService : Service() {

    companion object {
        const val ACTION_START = "com.waz.hinditap.START_CAPTURE"
        const val ACTION_STOP = "com.waz.hinditap.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "hinditap_capture"
        private const val NOTIF_ID = 4201

        /** Set while the service is running, so the UI can reflect it. */
        @Volatile
        var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var selectionView: SelectionOverlayView? = null
    private var panel: View? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenW = 0
    private var screenH = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        TranslationEngine.loadPrefs(this)
        TtsHelper.init(this)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Android 14+: the foreground service must be up BEFORE the
                // MediaProjection token is consumed, or it throws.
                startAsForeground()

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)

                if (data == null) {
                    toast("Screen capture permission nahi mili.")
                    stopEverything()
                    return START_NOT_STICKY
                }

                startProjection(resultCode, data)
                showBubble()
                isRunning = true
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- foreground

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "HindiTap screen reader",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("HindiTap chal raha hai")
            .setContentText("Bubble tap karke paragraph select karein")
            .setSmallIcon(R.drawable.ic_tile_hindi)
            .addAction(
                Notification.Action.Builder(null, "Band karein", stopIntent).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------------------------------------------------------------- projection

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        // Android 14+ requires a registered callback before createVirtualDisplay().
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopEverything() }
            }
        }, main)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)

        virtualDisplay = projection?.createVirtualDisplay(
            "HindiTapCapture",
            screenW, screenH, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble() {
        if (bubble != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - 220
            y = screenH / 3
        }

        // Draggable, but a short press without movement counts as a tap.
        var downX = 0f; var downY = 0f
        var origX = 0; var origY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    origX = params.x; origY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    params.x = origX + dx
                    params.y = origY + dy
                    windowManager.updateViewLayout(view, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) beginRegionSelection()
                    true
                }

                else -> false
            }
        }

        windowManager.addView(view, params)
        bubble = view
    }

    // ---------------------------------------------------------------- selection

    private fun beginRegionSelection() {
        if (selectionView != null) return
        TtsHelper.stop()
        removePanel()

        val view = SelectionOverlayView(
            this,
            onRegionSelected = { rect ->
                removeSelection()
                // Let the overlay actually disappear from the screen before the
                // frame is grabbed, otherwise the dim layer lands in the capture.
                main.postDelayed({ captureAndTranslate(rect) }, 220)
            },
            onCancelled = { removeSelection() }
        )

        // NO_LIMITS + IN_SCREEN make this window span the entire physical screen,
        // status bar and cutout included, so what is drawn lines up exactly with
        // what gets cropped out of the captured frame.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        bubble?.visibility = View.GONE
        windowManager.addView(view, params)
        selectionView = view
    }

    private fun removeSelection() {
        selectionView?.let { runCatching { windowManager.removeView(it) } }
        selectionView = null
        bubble?.visibility = View.GONE   // stays hidden until capture is done
    }

    // ---------------------------------------------------------------- capture + OCR

    private fun captureAndTranslate(region: Rect) {
        val bitmap = grabScreen()
        bubble?.visibility = View.VISIBLE

        if (bitmap == null) {
            toast("Screen capture fail. Service band karke dobara chalu karein.")
            return
        }

        val crop = cropSafely(bitmap, region)
        if (crop == null) {
            toast("Box bahut chhota hai.")
            return
        }

        showPanel("Padh raha hoon…")

        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val raw = result.text
                    .replace("-\n", "")   // de-hyphenate words broken across lines
                    .replace("\n", " ")
                    .trim()

                if (raw.isEmpty()) {
                    updatePanel("Is box mein koi text nahi mila. Thoda bada box banaiye, ya zoom karke try karein.")
                    return@addOnSuccessListener
                }

                updatePanel("Translating…")
                TranslationEngine.translateToHindi(
                    text = raw,
                    context = this,
                    onError = { msg -> main.post { updatePanel(msg) } },
                    onResult = { hindi, _ -> main.post { updatePanel(hindi, raw) } }
                )
            }
            .addOnFailureListener { e ->
                updatePanel("OCR fail: ${e.localizedMessage}")
            }
    }

    private fun grabScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null

        return try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val full = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)

            // Trim the stride padding off the right edge.
            Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun cropSafely(src: Bitmap, r: Rect): Bitmap? {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val right = r.right.coerceIn(left + 1, src.width)
        val bottom = r.bottom.coerceIn(top + 1, src.height)

        val w = right - left
        val h = bottom - top
        if (w < 20 || h < 12) return null

        val crop = Bitmap.createBitmap(src, left, top, w, h)

        // Newspaper print is small, and a single-word box is smaller still.
        // ML Kit wants roughly 32px of character height, so scale harder the
        // smaller the selection is.
        val factor = when {
            w < 300 -> 4
            w < 700 -> 3
            w < 1200 -> 2
            else -> 1
        }

        return if (factor == 1) crop
        else Bitmap.createScaledBitmap(crop, w * factor, h * factor, true)
    }

    // ---------------------------------------------------------------- result panel

    private fun showPanel(status: String) {
        removePanel()
        bubble?.visibility = View.VISIBLE

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        // A fixed height, not WRAP_CONTENT. A long paragraph used to stretch
        // this panel over the whole screen, pushing Close and Copy out of view
        // and leaving no way to dismiss the overlay at all.
        val panelHeight = (screenH * 0.55f).toInt()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        view.findViewById<TextView>(R.id.panelResult).text = status

        view.findViewById<View>(R.id.panelClose).setOnClickListener { removePanel() }

        view.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                removePanel()
                true
            } else {
                false
            }
        }

        windowManager.addView(view, params)
        panel = view
    }

    private fun updatePanel(text: String, original: String? = null) {
        val view = panel ?: run { showPanel(text); panel } ?: return

        view.findViewById<TextView>(R.id.panelResult).text = text

        view.findViewById<TextView>(R.id.panelSource).apply {
            if (original.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                this.text = original
            }
        }

        view.findViewById<View>(R.id.panelCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Hindi", text))
            toast("Copy ho gaya")
        }

        // Pronunciation: speak the English that was actually on the page.
        view.findViewById<View>(R.id.panelSpeakEn).apply {
            visibility = if (original.isNullOrBlank()) View.GONE else View.VISIBLE
            setOnClickListener {
                TtsHelper.speak(this@ScreenTranslateService, original.orEmpty(), TtsHelper.ENGLISH)
                    ?.let { toast(it) }
            }
        }

        view.findViewById<View>(R.id.panelSpeakHi).setOnClickListener {
            TtsHelper.speak(this@ScreenTranslateService, text, TtsHelper.HINDI)
                ?.let { toast(it) }
        }
    }

    private fun removePanel() {
        TtsHelper.stop()
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    // ---------------------------------------------------------------- teardown

    private fun stopEverything() {
        isRunning = false
        removePanel()
        removeSelection()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null

        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        TtsHelper.shutdown()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun toast(msg: String) {
        main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
