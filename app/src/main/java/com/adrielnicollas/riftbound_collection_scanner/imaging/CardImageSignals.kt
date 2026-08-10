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
        DomainColor("Body", listOf(16f..32f)),
        DomainColor("Order", listOf(33f..70f)),
        DomainColor("Calm", listOf(85f..155f)),
        DomainColor("Mind", listOf(190f..240f)),
        DomainColor("Chaos", listOf(255f..310f)),
    )

    fun decode(file: File): Bitmap? = BitmapFactory.decodeFile(file.absolutePath)

    fun cropCost(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.04f, 0.03f, 0.23f, 0.17f)

    fun cropRuneCostNumber(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.04f, 0.03f, 0.19f, 0.15f)

    fun cropPowerCostSymbol(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.035f, 0.085f, 0.185f, 0.275f)

    fun cropMight(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.76f, 0.03f, 0.97f, 0.17f)

    fun cropMightNumber(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.80f, 0.035f, 0.95f, 0.145f)

    fun cropTypeTags(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.045f, 0.455f, 0.62f, 0.512f)

    fun cropNameBand(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.055f, 0.515f, 0.93f, 0.61f)

    fun cropEffectText(bitmap: Bitmap): Bitmap {
        val bottom = findLoreMarkerTopFraction(bitmap)
            ?.let { markerTop -> (markerTop - 0.006f).coerceIn(0.765f, 0.89f) }
            ?: 0.89f
        return bitmap.cropFraction(0.055f, 0.59f, 0.945f, bottom)
    }

    fun cropLoreBox(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.055f, 0.795f, 0.93f, 0.905f)

    fun cropLoreMarker(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.055f, 0.795f, 0.145f, 0.855f)

    fun cropFooterNumber(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.015f, 0.89f, 0.38f, 0.985f)

    fun cropDomainSymbol(bitmap: Bitmap): Bitmap {
        return cropDomainSymbolFixed(bitmap)
    }

    fun cropDomainSymbolFixed(bitmap: Bitmap): Bitmap = bitmap.cropFraction(0.835f, 0.79f, 0.995f, 0.965f)

    fun detectDomainByColor(domainCrop: Bitmap): String {
        val mask = BooleanArray(domainCrop.width * domainCrop.height)
        val hsv = FloatArray(3)
        for (y in 0 until domainCrop.height) {
            for (x in 0 until domainCrop.width) {
                Color.colorToHSV(domainCrop.getPixel(x, y), hsv)
                if (hsv[1] >= 0.30f &&
                    hsv[2] >= 0.25f &&
                    domainColors.any { color -> color.hueRanges.any { hsv[0] in it } }
                ) {
                    mask[y * domainCrop.width + x] = true
                }
            }
        }

        val component = findBestSymbolComponent(mask, domainCrop.width, domainCrop.height) ?: return ""
        val counts = mutableMapOf<String, Int>()
        for (y in component.top..component.bottom) {
            for (x in component.left..component.right) {
                val index = y * domainCrop.width + x
                if (!mask[index]) continue
                Color.colorToHSV(domainCrop.getPixel(x, y), hsv)
                val domain = domainColors.firstOrNull { color -> color.hueRanges.any { hsv[0] in it } }?.domain ?: continue
                counts[domain] = counts.getOrDefault(domain, 0) + 1
            }
        }

        val sorted = counts.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull() ?: return ""
        val second = sorted.getOrNull(1)?.value ?: 0
        val total = counts.values.sum().coerceAtLeast(1)
        val confidence = best.value.toFloat() / total.toFloat()
        return best.key.takeIf {
            best.value >= 40 &&
                confidence >= 0.42f &&
                best.value >= second * 1.18f
        }.orEmpty()
    }

    fun detectPowerCostByColor(powerCrop: Bitmap): String {
        val component = findBestColorComponent(
            bitmap = powerCrop,
            minArea = 45,
            minAspect = 0.25f,
            maxAspect = 1.45f,
            minFillRatio = 0.20f,
        ) ?: return ""

        val domain = dominantDomain(powerCrop, component).takeIf { it.isNotBlank() } ?: return ""
        val heightRatio = (component.bottom - component.top + 1).toFloat() / powerCrop.height.toFloat()
        val amount = if (heightRatio >= 0.46f) 2 else 1
        return "$amount $domain"
    }

    private fun findBestColorComponent(
        bitmap: Bitmap,
        minArea: Int,
        minAspect: Float,
        maxAspect: Float,
        minFillRatio: Float,
    ): Component? {
        val mask = BooleanArray(bitmap.width * bitmap.height)
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                if (hsv[1] >= 0.30f &&
                    hsv[2] >= 0.24f &&
                    domainColors.any { color -> color.hueRanges.any { hsv[0] in it } }
                ) {
                    mask[y * bitmap.width + x] = true
                }
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        var best: Component? = null
        var bestScore = 0f

        for (startY in 0 until bitmap.height) {
            for (startX in 0 until bitmap.width) {
                val startIndex = startY * bitmap.width + startX
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
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    area += 1
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)

                    addNeighbor(mask, visited, queue, x + 1, y, bitmap.width, bitmap.height)
                    addNeighbor(mask, visited, queue, x - 1, y, bitmap.width, bitmap.height)
                    addNeighbor(mask, visited, queue, x, y + 1, bitmap.width, bitmap.height)
                    addNeighbor(mask, visited, queue, x, y - 1, bitmap.width, bitmap.height)
                }

                val boxWidth = right - left + 1
                val boxHeight = bottom - top + 1
                val aspect = boxWidth.toFloat() / boxHeight.coerceAtLeast(1)
                val fillRatio = area.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)
                val widthFraction = boxWidth.toFloat() / bitmap.width.toFloat()
                val heightFraction = boxHeight.toFloat() / bitmap.height.toFloat()
                val centerX = (left + right + 1).toFloat() / (bitmap.width * 2f)
                val centerY = (top + bottom + 1).toFloat() / (bitmap.height * 2f)
                if (area < minArea || aspect !in minAspect..maxAspect || fillRatio < minFillRatio) continue
                if (centerX !in 0.12f..0.62f || centerY !in 0.06f..0.72f) continue

                val singleIconLike = widthFraction in 0.14f..0.52f &&
                    heightFraction in 0.10f..0.34f &&
                    aspect in 0.55f..1.45f
                val stackedIconLike = widthFraction in 0.14f..0.52f &&
                    heightFraction in 0.30f..0.68f &&
                    aspect in 0.25f..0.95f
                if (!singleIconLike && !stackedIconLike) continue

                val expectedXBonus = 1f - abs(centerX - 0.33f).coerceAtMost(0.33f)
                val expectedYBonus = 1f - abs(centerY - 0.34f).coerceAtMost(0.34f)
                val score = area * max(fillRatio, 0.2f) * max(expectedXBonus, 0.35f) * max(expectedYBonus, 0.35f)
                if (score > bestScore) {
                    bestScore = score
                    best = Component(left, top, right, bottom)
                }
            }
        }

        return best
    }

    private fun dominantDomain(bitmap: Bitmap, component: Component): String {
        val counts = mutableMapOf<String, Int>()
        val hsv = FloatArray(3)
        for (y in component.top..component.bottom) {
            for (x in component.left..component.right) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                if (hsv[1] < 0.30f || hsv[2] < 0.24f) continue
                val domain = domainColors.firstOrNull { color -> color.hueRanges.any { hsv[0] in it } }?.domain ?: continue
                counts[domain] = counts.getOrDefault(domain, 0) + 1
            }
        }

        val sorted = counts.entries.sortedByDescending { it.value }
        val best = sorted.firstOrNull() ?: return ""
        val second = sorted.getOrNull(1)?.value ?: 0
        val total = counts.values.sum().coerceAtLeast(1)
        val confidence = best.value.toFloat() / total.toFloat()
        return best.key.takeIf {
            best.value >= 40 &&
                confidence >= 0.42f &&
                best.value >= second * 1.18f
        }.orEmpty()
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
                if (centerX < roiLeft + roiWidth * 0.55f || centerY < roiTop + roiHeight * 0.45f) continue

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

    private fun findBestSymbolComponent(
        mask: BooleanArray,
        imageWidth: Int,
        imageHeight: Int,
    ): Component? {
        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        var best: Component? = null
        var bestScore = 0f

        for (startY in 0 until imageHeight) {
            for (startX in 0 until imageWidth) {
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
                val fillRatio = area.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)
                if (area < 35 || aspect < 0.55f || aspect > 1.75f || fillRatio < 0.22f) continue
                if (boxWidth > imageWidth * 0.72f || boxHeight > imageHeight * 0.72f) continue

                val centerX = (left + right) / 2f / imageWidth
                val centerY = (top + bottom) / 2f / imageHeight
                val circleScore = 1f - abs(1f - aspect)
                val bottomRightBias = 1f + centerX * 0.35f + centerY * 0.55f
                val score = area * max(circleScore, 0.2f) * max(fillRatio, 0.2f) * bottomRightBias

                if (score > bestScore) {
                    bestScore = score
                    best = Component(left, top, right, bottom)
                }
            }
        }

        return best
    }

    private fun findLoreMarkerTopFraction(bitmap: Bitmap): Float? {
        val left = (bitmap.width * 0.025f).roundToInt()
        val right = (bitmap.width * 0.16f).roundToInt()
        val top = (bitmap.height * 0.70f).roundToInt()
        val bottom = (bitmap.height * 0.88f).roundToInt()
        val mask = BooleanArray(bitmap.width * bitmap.height)
        val hsv = FloatArray(3)

        for (y in top until bottom.coerceAtMost(bitmap.height)) {
            for (x in left until right.coerceAtMost(bitmap.width)) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv)
                if (hsv[1] >= 0.32f && hsv[2] >= 0.25f) {
                    mask[y * bitmap.width + x] = true
                }
            }
        }

        val visited = BooleanArray(mask.size)
        val queue = ArrayDeque<Int>()
        var best: Component? = null
        var bestScore = 0f

        for (startY in top until bottom.coerceAtMost(bitmap.height)) {
            for (startX in left until right.coerceAtMost(bitmap.width)) {
                val startIndex = startY * bitmap.width + startX
                if (!mask[startIndex] || visited[startIndex]) continue

                queue.clear()
                queue.add(startIndex)
                visited[startIndex] = true
                var componentLeft = startX
                var componentRight = startX
                var componentTop = startY
                var componentBottom = startY
                var area = 0

                while (!queue.isEmpty()) {
                    val index = queue.removeFirst()
                    val x = index % bitmap.width
                    val y = index / bitmap.width
                    area += 1
                    componentLeft = minOf(componentLeft, x)
                    componentRight = maxOf(componentRight, x)
                    componentTop = minOf(componentTop, y)
                    componentBottom = maxOf(componentBottom, y)

                    addNeighbor(mask, visited, queue, x + 1, y, bitmap.width, bitmap.height)
                    addNeighbor(mask, visited, queue, x - 1, y, bitmap.width, bitmap.height)
                    addNeighbor(mask, visited, queue, x, y + 1, bitmap.width, bitmap.height)
                    addNeighbor(mask, visited, queue, x, y - 1, bitmap.width, bitmap.height)
                }

                val boxWidth = componentRight - componentLeft + 1
                val boxHeight = componentBottom - componentTop + 1
                val widthFraction = boxWidth.toFloat() / bitmap.width
                val heightFraction = boxHeight.toFloat() / bitmap.height
                val aspect = boxWidth.toFloat() / boxHeight.coerceAtLeast(1)
                val fillRatio = area.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)

                if (area < 18) continue
                if (widthFraction !in 0.006f..0.055f || heightFraction !in 0.006f..0.045f) continue
                if (aspect !in 0.45f..2.1f || fillRatio < 0.18f) continue

                val score = area * fillRatio * (1f - abs(1f - aspect).coerceAtMost(1f))
                if (score > bestScore) {
                    bestScore = score
                    best = Component(componentLeft, componentTop, componentRight, componentBottom)
                }
            }
        }

        return best?.top?.toFloat()?.div(bitmap.height)
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
