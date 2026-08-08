package com.adrielnicollas.riftbound_collection_scanner.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object CardImageCropper {
    fun cropToGuide(
        sourceFile: File,
        destinationFile: File,
        previewSize: Size,
        guideRect: RectF,
    ): Boolean {
        val orientedBitmap = decodeOrientedBitmap(sourceFile) ?: return false
        return try {
            val cropRect = guideRect.toBitmapRect(
                imageWidth = orientedBitmap.width,
                imageHeight = orientedBitmap.height,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
            )
            if (cropRect.width() < 10f || cropRect.height() < 10f) return false

            val left = cropRect.left.roundToInt().coerceIn(0, orientedBitmap.width - 1)
            val top = cropRect.top.roundToInt().coerceIn(0, orientedBitmap.height - 1)
            val right = cropRect.right.roundToInt().coerceIn(left + 1, orientedBitmap.width)
            val bottom = cropRect.bottom.roundToInt().coerceIn(top + 1, orientedBitmap.height)
            val cropped = Bitmap.createBitmap(orientedBitmap, left, top, right - left, bottom - top)

            destinationFile.parentFile?.mkdirs()
            FileOutputStream(destinationFile).use { output ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 95, output)
            }
            cropped.recycle()
            true
        } finally {
            orientedBitmap.recycle()
        }
    }

    private fun RectF.toBitmapRect(
        imageWidth: Int,
        imageHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): RectF {
        val scale = max(
            previewWidth.toFloat() / imageWidth.toFloat(),
            previewHeight.toFloat() / imageHeight.toFloat(),
        )
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val offsetX = (previewWidth - displayedWidth) / 2f
        val offsetY = (previewHeight - displayedHeight) / 2f

        return RectF(
            ((left - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
            ((top - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
            ((right - offsetX) / scale).coerceIn(0f, imageWidth.toFloat()),
            ((bottom - offsetY) / scale).coerceIn(0f, imageHeight.toFloat()),
        )
    }

    private fun decodeOrientedBitmap(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        }

        if (matrix.isIdentity) return bitmap

        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return oriented
    }
}
