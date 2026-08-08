package com.adrielnicollas.riftbound_collection_scanner.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.roundToInt

data class CardImageSignals(
    val cost: Int? = null,
    val might: Int? = null,
    val domain: String = "",
)

object CardImageSignalDetector {
    private val domainColors = listOf(
        DomainColor("Fury", listOf(345f..360f, 0f..15f)),
        DomainColor("Body", listOf(16f..35f)),
        DomainColor("Order", listOf(36f..65f)),
        DomainColor("Calm", listOf(85f..155f)),
        DomainColor("Mind", listOf(190f..240f)),
        DomainColor("Chaos", listOf(255f..310f)),
    )

    fun detectColorSignals(file: File): CardImageSignals {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return CardImageSignals()
        return try {
            CardImageSignals(domain = detectDomain(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    fun cropCost(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.04f, 0.03f, 0.23f, 0.17f)

    fun cropMight(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.76f, 0.03f, 0.97f, 0.17f)

    private fun detectDomain(bitmap: Bitmap): String {
        val regions = listOf(
            bitmap.cropFraction(0.88f, 0.90f, 0.99f, 0.99f),
            bitmap.cropFraction(0.03f, 0.11f, 0.16f, 0.25f),
        )

        return try {
            regions
                .mapNotNull { sampleDomain(it) }
                .distinct()
                .joinToString(separator = " / ")
        } finally {
            regions.forEach { it.recycle() }
        }
    }

    private fun sampleDomain(bitmap: Bitmap): String? {
        val counts = mutableMapOf<String, Int>()
        val hsv = FloatArray(3)
        val stepX = (bitmap.width / 32).coerceAtLeast(1)
        val stepY = (bitmap.height / 32).coerceAtLeast(1)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]
                if (saturation > 0.35f && value > 0.25f) {
                    domainColors.firstOrNull { color -> color.hueRanges.any { hue in it } }
                        ?.let { color -> counts[color.domain] = counts.getOrDefault(color.domain, 0) + 1 }
                }
                x += stepX
            }
            y += stepY
        }

        return counts.maxByOrNull { it.value }
            ?.takeIf { it.value >= 4 }
            ?.key
    }

    private fun Bitmap.cropFraction(left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (width * left).roundToInt().coerceIn(0, width - 1)
        val y = (height * top).roundToInt().coerceIn(0, height - 1)
        val cropRight = (width * right).roundToInt().coerceIn(x + 1, width)
        val cropBottom = (height * bottom).roundToInt().coerceIn(y + 1, height)
        return Bitmap.createBitmap(this, x, y, cropRight - x, cropBottom - y)
    }

    private data class DomainColor(
        val domain: String,
        val hueRanges: List<ClosedFloatingPointRange<Float>>,
    )
}
