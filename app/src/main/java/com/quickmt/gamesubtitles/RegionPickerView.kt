package com.quickmt.gamesubtitles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Fullscreen touch-and-drag overlay allowing the user to select the exact
 * English subtitle area directly on top of a running Android game.
 */
@SuppressLint("ViewConstructor")
class RegionPickerView(
    context: Context,
    initialLeft: Float,
    initialTop: Float,
    initialRight: Float,
    initialBottom: Float,
    private val onConfirm: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    private val onCancel: () -> Unit
) : FrameLayout(context) {

    private var normLeft = initialLeft
    private var normTop = initialTop
    private var normRight = initialRight
    private var normBottom = initialBottom

    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    private val drawView = object : View(context) {
        private val dimPaint = Paint().apply { color = Color.argb(135, 0, 0, 0) }
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            val rect = RectF(
                normLeft * width,
                normTop * height,
                normRight * width,
                normBottom * height
            )
            canvas.drawRect(rect, clearPaint)
            canvas.drawRect(rect, borderPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (width <= 0 || height <= 0) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    dragging = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val l = min(startX, event.x) / width
                        val r = max(startX, event.x) / width
                        val t = min(startY, event.y) / height
                        val b = max(startY, event.y) / height
                        if (r - l >= 0.05f && b - t >= 0.03f) {
                            normLeft = l.coerceIn(0f, 0.95f)
                            normRight = r.coerceIn(0.05f, 1f)
                            normTop = t.coerceIn(0f, 0.95f)
                            normBottom = b.coerceIn(0.05f, 1f)
                            invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    init {
        addView(drawView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.argb(235, 15, 23, 42))
        }

        val hint = TextView(context).apply {
            text = "İngilizce altyazı bölgesini parmağınızla sürükleyip seçin"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val btnCancel = Button(context).apply {
            text = "İptal"
            setOnClickListener { onCancel() }
        }
        bar.addView(btnCancel)

        val btnOk = Button(context).apply {
            text = "Onayla"
            setOnClickListener { onConfirm(normLeft, normTop, normRight, normBottom) }
        }
        bar.addView(btnOk)

        val barLp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP)
        barLp.topMargin = 32
        addView(bar, barLp)
    }
}
