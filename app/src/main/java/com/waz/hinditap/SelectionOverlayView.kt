package com.waz.hinditap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen transparent view. The user drags a box over the paragraph they want.
 * On release it reports the rectangle in screen coordinates.
 */
class SelectionOverlayView(
    context: Context,
    private val onRegionSelected: (Rect) -> Unit,
    private val onCancelled: () -> Unit
) : View(context) {

    // Local coordinates — used only for drawing inside this view.
    private var startX = 0f
    private var startY = 0f
    private var currX = 0f
    private var currY = 0f

    // Raw screen coordinates — used for the reported rectangle. These MUST be
    // separate: the captured frame covers the whole physical screen (status bar
    // included), while this view's local origin sits below any window inset.
    // Using local coords to crop a full-screen bitmap shifts the crop upward by
    // the status bar height, which silently grabs the wrong paragraph.
    private var rawStartX = 0f
    private var rawStartY = 0f
    private var rawCurrX = 0f
    private var rawCurrY = 0f

    private var dragging = false

    private val dimPaint = Paint().apply {
        color = Color.argb(90, 0, 0, 0)
    }

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#4CD7A0")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val hintPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    init {
        // Needed so the CLEAR xfermode punches a real hole instead of painting black.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        if (dragging) {
            val r = currentRect()
            canvas.drawRect(r, clearPaint)
            canvas.drawRect(r, borderPaint)
        } else {
            canvas.drawText(
                "Paragraph ke upar box banaiye",
                width / 2f,
                height / 2f,
                hintPaint
            )
            canvas.drawText(
                "(bahar tap = cancel)",
                width / 2f,
                height / 2f + 56f,
                hintPaint
            )
        }
    }

    /** For drawing — local view coordinates. */
    private fun currentRect(): Rect = Rect(
        min(startX, currX).toInt(),
        min(startY, currY).toInt(),
        max(startX, currX).toInt(),
        max(startY, currY).toInt()
    )

    /** For cropping the captured frame — absolute screen coordinates. */
    private fun currentScreenRect(): Rect = Rect(
        min(rawStartX, rawCurrX).toInt(),
        min(rawStartY, rawCurrY).toInt(),
        max(rawStartX, rawCurrX).toInt(),
        max(rawStartY, rawCurrY).toInt()
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                currX = event.x; currY = event.y
                rawStartX = event.rawX; rawStartY = event.rawY
                rawCurrX = event.rawX; rawCurrY = event.rawY
                dragging = true
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currX = event.x; currY = event.y
                rawCurrX = event.rawX; rawCurrY = event.rawY
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                // A tap rather than a drag means "cancel". Thresholds are small
                // so that boxing a single word still counts as a selection.
                if (abs(currX - startX) < 24 || abs(currY - startY) < 12) {
                    onCancelled()
                } else {
                    onRegionSelected(currentScreenRect())
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
