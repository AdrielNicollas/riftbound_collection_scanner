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
    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val sectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
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
        drawSections(canvas)
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

    private fun drawSections(canvas: Canvas) {
        sections.forEach { section ->
            val rect = section.toRect(guide)
            sectionPaint.color = section.color
            sectionFillPaint.color = Color.argb(28, Color.red(section.color), Color.green(section.color), Color.blue(section.color))
            canvas.drawRoundRect(rect, 8f, 8f, sectionFillPaint)
            canvas.drawRoundRect(rect, 8f, 8f, sectionPaint)
            canvas.drawText(section.label, rect.left + 8f, rect.top + 28f, labelPaint)
        }
    }

    private companion object {
        private const val CARD_WIDTH_RATIO = 63f
        private const val CARD_HEIGHT_RATIO = 88f
        private val sections = listOf(
            GuideSection("custo", RectF(0.04f, 0.03f, 0.23f, 0.17f), Color.rgb(83, 196, 255)),
            GuideSection("power", RectF(0.035f, 0.085f, 0.185f, 0.275f), Color.rgb(255, 208, 85)),
            GuideSection("might", RectF(0.80f, 0.035f, 0.95f, 0.145f), Color.rgb(255, 125, 125)),
            GuideSection("tipo", RectF(0.045f, 0.455f, 0.62f, 0.512f), Color.rgb(152, 255, 152)),
            GuideSection("nome", RectF(0.055f, 0.515f, 0.93f, 0.61f), Color.rgb(255, 255, 255)),
            GuideSection("efeito", RectF(0.055f, 0.59f, 0.945f, 0.805f), Color.rgb(187, 142, 255)),
            GuideSection("dominio", RectF(0.835f, 0.79f, 0.995f, 0.965f), Color.rgb(255, 222, 70)),
        )
    }

    private data class GuideSection(
        val label: String,
        val fraction: RectF,
        val color: Int,
    ) {
        fun toRect(guide: RectF): RectF {
            return RectF(
                guide.left + guide.width() * fraction.left,
                guide.top + guide.height() * fraction.top,
                guide.left + guide.width() * fraction.right,
                guide.top + guide.height() * fraction.bottom,
            )
        }
    }
}
