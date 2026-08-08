package com.adrielnicollas.riftbound_collection_scanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class CardGuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val guide = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    fun guideRect(): RectF = RectF(guide)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGuideRect(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (guide.isEmpty) return

        canvas.drawRect(0f, 0f, width.toFloat(), guide.top, dimPaint)
        canvas.drawRect(0f, guide.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, guide.top, guide.left, guide.bottom, dimPaint)
        canvas.drawRect(guide.right, guide.top, width.toFloat(), guide.bottom, dimPaint)
        canvas.drawRoundRect(guide, 18f, 18f, borderPaint)
        drawCorners(canvas)
    }

    private fun updateGuideRect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) {
            guide.setEmpty()
            return
        }

        val cardAspectRatio = CARD_WIDTH_RATIO / CARD_HEIGHT_RATIO
        var guideWidth = width * 0.78f
        var guideHeight = guideWidth / cardAspectRatio
        val maxHeight = height * 0.76f
        if (guideHeight > maxHeight) {
            guideHeight = maxHeight
            guideWidth = guideHeight * cardAspectRatio
        }

        val left = (width - guideWidth) / 2f
        val top = (height - guideHeight) / 2f
        guide.set(left, top, left + guideWidth, top + guideHeight)
    }

    private fun drawCorners(canvas: Canvas) {
        val corner = min(guide.width(), guide.height()) * 0.12f

        canvas.drawLine(guide.left, guide.top, guide.left + corner, guide.top, cornerPaint)
        canvas.drawLine(guide.left, guide.top, guide.left, guide.top + corner, cornerPaint)

        canvas.drawLine(guide.right, guide.top, guide.right - corner, guide.top, cornerPaint)
        canvas.drawLine(guide.right, guide.top, guide.right, guide.top + corner, cornerPaint)

        canvas.drawLine(guide.left, guide.bottom, guide.left + corner, guide.bottom, cornerPaint)
        canvas.drawLine(guide.left, guide.bottom, guide.left, guide.bottom - corner, cornerPaint)

        canvas.drawLine(guide.right, guide.bottom, guide.right - corner, guide.bottom, cornerPaint)
        canvas.drawLine(guide.right, guide.bottom, guide.right, guide.bottom - corner, cornerPaint)
    }

    private companion object {
        private const val CARD_WIDTH_RATIO = 63f
        private const val CARD_HEIGHT_RATIO = 88f
    }
}
