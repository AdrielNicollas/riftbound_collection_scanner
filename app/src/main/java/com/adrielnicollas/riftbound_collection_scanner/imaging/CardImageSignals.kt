package com.adrielnicollas.riftbound_collection_scanner.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class CardImageSignals(
    val cost: Int? = null,
    val powerCost: String = "",
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

    fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    fun cropCost(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.04f, 0.03f, 0.23f, 0.17f)

    fun cropRuneCostNumber(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.04f, 0.03f, 0.19f, 0.15f)

    fun cropPowerCostSymbol(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.045f, 0.135f, 0.165f, 0.245f)

    fun cropMight(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.76f, 0.03f, 0.97f, 0.17f)

    fun cropMightNumber(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.84f, 0.035f, 0.96f, 0.145f)

    fun cropDomainSymbol(bitmap: Bitmap): Bitmap {
        return cropDetectedDomainSymbol(bitmap) ?: bitmap.cropFraction(0.86f, 0.82f, 0.99f, 0.97f)
    }

    private fun cropDetectedDomainSymbol(bitmap: Bitmap): Bitmap? {
        val smallMaxSize = 700f
        val scale = minOf(1f, smallMaxSize / max(bitmap.width, bitmap.height).toFloat())
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }

        return try {
            val roiLeft = (small.width * 0.78f).roundToInt()
            val roiTop = (small.height * 0.82f).roundToInt()
            val mask = BooleanArray(small.width * small.height)
            val hsv = FloatArray(3)

            for (y in roiTop until small.height) {
                for (x in roiLeft until small.width) {
                    Color.colorToHSV(small.getPixel(x, y), hsv)
                    if (hsv[1] >= 0.28f &&
                        hsv[2] >= 0.22f &&
                        domainColors.any { color -> color.hueRanges.any { hsv[0] in it } }
                    ) {
                        mask[y * small.width + x] = true
                    }
                }
            }

            val component = findBestComponent(
                mask = mask,
                imageWidth = small.width,
                imageHeight = small.height,
                roiLeft = roiLeft,
                roiTop = roiTop,
            ) ?: return null

            val centerX = ((component.left + component.right) / 2f) / scale
            val centerY = ((component.top + component.bottom) / 2f) / scale
            val cropSize = max(bitmap.width, bitmap.height) * 0.06f
            bitmap.cropSquareInside(centerX, centerY, cropSize)
        } finally {
            if (small !== bitmap) {
                small.recycle()
            }
        }
    }

    private fun findBestComponent(
        mask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
        roiLeft: Int,
        roiTop: Int,
    ): Component? {
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        var best: Component? = null
        var bestScore = 0f
        val roiWidth = imageWidth - roiLeft
        val roiHeight = imageHeight - roiTop

        for (startY in roiTop until imageHeight) {
            for (startX in roiLeft until imageWidth) {
                val startIndex = startY * imageWidth + startX
                if (!mask[startIndex] || visited[startIndex]) continue

                queue.clear()
                queue.add(startIndex)
                visited[startIndex] = true
                var left = startX
                var right = startX
                var top = startY
                var bottom = startY
                var area = 0

                while (!queue.isEmpty()) {
                    val index = queue.removeFirst()
                    val x = index % imageWidth
                    val y = index / imageWidth
                    area += 1
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)

                    addNeighbor(mask, visited, queue, x + 1, y, imageWidth, imageHeight)
                    addNeighbor(mask, visited, queue, x - 1, y, imageWidth, imageHeight)
                    addNeighbor(mask, visited, queue, x, y + 1, imageWidth, imageHeight)
                    addNeighbor(mask, visited, queue, x, y - 1, imageWidth, imageHeight)
                }

                val boxWidth = right - left + 1
                val boxHeight = bottom - top + 1
                val aspect = boxWidth.toFloat() / boxHeight.coerceAtLeast(1)
                if (area < 8 || aspect < 0.35f || aspect > 2.8f) continue
                if (boxWidth > roiWidth * 0.38f || boxHeight > roiHeight * 0.5f) continue

                val centerX = (left + right) / 2f
                val centerY = (top + bottom) / 2f
                val bottomRightBias = (centerX - roiLeft) / roiWidth + (centerY - roiTop) / roiHeight
                val fillRatio = area.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)
                val circleScore = 1f - abs(1f - aspect)
                val score = area * (1f + bottomRightBias * 3f) * max(circleScore, 0.2f) * max(fillRatio, 0.2f)

                if (score > bestScore) {
                    bestScore = score
                    best = Component(left, top, right, bottom)
                }
            }
        }

        return best
    }

    private fun addNeighbor(
        mask: BooleanArray,
        visited: BooleanArray,
        queue: ArrayDeque<Int>,
        x: Int,
        y: Int,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        if (x !in 0 until imageWidth || y !in 0 until imageHeight) return
        val index = y * imageWidth + x
        if (mask[index] && !visited[index]) {
            visited[index] = true
            queue.add(index)
        }
    }

    private fun Bitmap.cropFraction(left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (width * left).roundToInt().coerceIn(0, width - 1)
        val y = (height * top).roundToInt().coerceIn(0, height - 1)
        val cropRight = (width * right).roundToInt().coerceIn(x + 1, width)
        val cropBottom = (height * bottom).roundToInt().coerceIn(y + 1, height)
        return Bitmap.createBitmap(this, x, y, cropRight - x, cropBottom - y)
    }

    private fun Bitmap.cropSquareInside(centerX: Float, centerY: Float, cropSize: Float): Bitmap {
        val size = cropSize.roundToInt().coerceIn(1, minOf(width, height))
        val x = (centerX - size / 2f).roundToInt().coerceIn(0, width - size)
        val y = (centerY - size / 2f).roundToInt().coerceIn(0, height - size)
        return Bitmap.createBitmap(this, x, y, size, size)
    }

    private data class DomainColor(
        val domain: String,
        val hueRanges: List<ClosedFloatingPointRange<Float>>,
    )

    private data class Component(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )
}
