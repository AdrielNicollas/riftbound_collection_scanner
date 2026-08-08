package com.adrielnicollas.riftbound_collection_scanner.imaging

import android.content.Context
import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DomainFeedbackStore {
    fun saveCrop(context: Context, bitmap: Bitmap, prediction: DomainPrediction?): File {
        val timestamp = timestamp("yyyyMMdd_HHmmss_SSS")
        val imageFile = File(feedbackDir(context).apply { mkdirs() }, "domain_crop_$timestamp.jpg")
        FileOutputStream(imageFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        appendManifest(context, imageFile, prediction, timestamp)
        return imageFile
    }

    fun createExportZip(context: Context): File? {
        val feedbackDir = feedbackDir(context)
        val imageFiles = feedbackDir.listFiles { file ->
            file.isFile && file.extension.equals("jpg", ignoreCase = true)
        }?.sortedBy { file -> file.name }.orEmpty()
        if (imageFiles.isEmpty()) return null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFile = File(exportDir, "riftbound_domain_feedback_${timestamp("yyyyMMdd_HHmmss")}.zip")
        val manifestFile = manifestFile(context)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            if (manifestFile.exists()) {
                addFile(zip, manifestFile, "manifest.csv")
            }
            imageFiles.forEach { file ->
                addFile(zip, file, file.name)
            }
        }

        return zipFile
    }

    fun count(context: Context): Int {
        return feedbackDir(context).listFiles { file ->
            file.isFile && file.extension.equals("jpg", ignoreCase = true)
        }?.size ?: 0
    }

    private fun appendManifest(
        context: Context,
        imageFile: File,
        prediction: DomainPrediction?,
        timestamp: String,
    ) {
        val manifest = manifestFile(context)
        manifest.parentFile?.mkdirs()
        if (!manifest.exists()) {
            manifest.writeText("file,captured_at,predicted_domain,confidence\n", charset = Charsets.UTF_8)
        }

        val domain = prediction?.domain.orEmpty()
        val confidence = prediction?.confidence?.let { "%.4f".format(Locale.US, it) }.orEmpty()
        manifest.appendText(
            "${imageFile.name},$timestamp,$domain,$confidence\n",
            charset = Charsets.UTF_8,
        )
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        BufferedInputStream(FileInputStream(file)).use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun feedbackDir(context: Context): File = File(context.filesDir, "domain_feedback")

    private fun manifestFile(context: Context): File = File(feedbackDir(context), "manifest.csv")

    private fun timestamp(pattern: String): String {
        return SimpleDateFormat(pattern, Locale.US).format(Date())
    }
}
