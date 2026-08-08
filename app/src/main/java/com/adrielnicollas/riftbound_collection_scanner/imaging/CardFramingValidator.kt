package com.adrielnicollas.riftbound_collection_scanner.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.roundToInt

data class CardFramingResult(
    val isAcceptable: Boolean,
    val message: String,
)

object CardFramingValidator {
    fun validate(file: File): CardFramingResult {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: return CardFramingResult(false, "Nao consegui abrir a foto. Tenta novamente.")

        return try {
            val hasCost = hasLikelyCostMarker(bitmap)
            val hasDomain = hasLikelyDomainMarker(bitmap)

            when {
                hasCost && hasDomain -> CardFramingResult(true, "")
                !hasCost && !hasDomain -> CardFramingResult(
                    false,
                    "Centra a carta dentro da mira. Nao consegui ver o custo nem o dominio.",
                )
                !hasCost -> CardFramingResult(
                    false,
                    "Sobe ou centra melhor a carta. O custo ficou fora da mira.",
                )
                else -> CardFramingResult(
                    false,
                    "Baixa ou centra melhor a carta. O dominio ficou fora da mira.",
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasLikelyCostMarker(bitmap: Bitmap): Boolean {
        return sampleRegion(
            bitmap = bitmap,
            left = 0.0f,
            top = 0.0f,
            right = 0.30f,
            bottom = 0.24f,
        ) { _, saturation, value ->
            value >= 0.62f && saturation <= 0.38f
        } >= 0.035f
    }

    private fun hasLikelyDomainMarker(bitmap: Bitmap): Boolean {
        return sampleRegion(
            bitmap = bitmap,
            left = 0.72f,
            top = 0.78f,
            right = 1.0f,
            bottom = 1.0f,
        ) { hue, saturation, value ->
            saturation >= 0.28f &&
                value >= 0.22f &&
                DOMAIN_HUE_RANGES.any { range -> hue in range }
        } >= 0.006f
    }

    private fun sampleRegion(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        matches: (hue: Float, saturation: Float, value: Float) -> Boolean,
    ): Float {
        val xStart = (bitmap.width * left).roundToInt().coerceIn(0, bitmap.width - 1)
        val yStart = (bitmap.height * top).roundToInt().coerceIn(0, bitmap.height - 1)
        val xEnd = (bitmap.width * right).roundToInt().coerceIn(xStart + 1, bitmap.width)
        val yEnd = (bitmap.height * bottom).roundToInt().coerceIn(yStart + 1, bitmap.height)
        val stepX = ((xEnd - xStart) / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = ((yEnd - yStart) / SAMPLE_GRID).coerceAtLeast(1)
        val hsv = FloatArray(3)
        var total = 0
        var matched = 0

        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                total += 1
                if (matches(hsv[0], hsv[1], hsv[2])) {
                    matched += 1
                }
                x += stepX
            }
            y += stepY
        }

        return if (total == 0) 0f else matched.toFloat() / total.toFloat()
    }

    private val DOMAIN_HUE_RANGES = listOf(
        345f..360f,
        0f..15f,
        16f..35f,
        36f..65f,
        85f..155f,
        190f..240f,
        255f..310f,
    )
    private const val SAMPLE_GRID = 44
}
