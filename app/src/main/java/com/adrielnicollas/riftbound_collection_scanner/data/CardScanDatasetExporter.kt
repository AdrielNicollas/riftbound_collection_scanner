package com.adrielnicollas.riftbound_collection_scanner.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

data class CardScanDatasetItem(
    val id: String,
    val imagePath: String,
    val name: String,
    val cardNumber: String,
    val cardSet: String,
    val cost: Int?,
    val powerCost: String,
    val might: Int?,
    val cardType: String,
    val domain: String,
    val effectText: String,
    val scannedAt: Long,
    val scanDate: String,
)

object CardScanDatasetExporter {
    fun fromDraft(draft: ScanDraftEntity): CardScanDatasetItem {
        return CardScanDatasetItem(
            id = "draft_${draft.id}",
            imagePath = draft.imagePath,
            name = draft.name,
            cardNumber = draft.cardNumber,
            cardSet = draft.cardSet,
            cost = draft.cost,
            powerCost = draft.powerCost,
            might = draft.might,
            cardType = draft.cardType,
            domain = draft.domain,
            effectText = draft.ocrText,
            scannedAt = draft.scannedAt,
            scanDate = draft.scanDate,
        )
    }

    fun createExportZip(context: Context, items: List<CardScanDatasetItem>): File? {
        val exportableItems = items
            .mapNotNull { item ->
                val imageFile = File(item.imagePath)
                if (imageFile.exists() && imageFile.isFile) item to imageFile else null
            }
        if (exportableItems.isEmpty()) return null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(exportDir, "riftbound_ocr_dataset_$timestamp.zip")
        val jsonItems = JSONArray()

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            exportableItems.forEachIndexed { index, (item, imageFile) ->
                val imageName = imageEntryName(index + 1, item, imageFile)
                zip.putNextEntry(ZipEntry(imageName))
                BufferedInputStream(FileInputStream(imageFile)).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()

                jsonItems.put(item.toJson(imageName))
            }

            zip.putNextEntry(ZipEntry("scans.json"))
            zip.write(
                JSONObject()
                    .put("version", 1)
                    .put("exportedAt", System.currentTimeMillis())
                    .put("itemCount", jsonItems.length())
                    .put("items", jsonItems)
                    .toString(2)
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }

        return zipFile
    }

    private fun CardScanDatasetItem.toJson(imageName: String): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("image", imageName)
            .put("scannedAt", scannedAt)
            .put("scanDate", scanDate)
            .put(
                "fields",
                JSONObject()
                    .put("name", name)
                    .put("cost", cost ?: JSONObject.NULL)
                    .put("powerCost", powerCost)
                    .put("might", might ?: JSONObject.NULL)
                    .put("domain", domain)
                    .put("type", cardType)
                    .put("effect", effectText)
                    .put("cardNumber", cardNumber)
                    .put("set", cardSet),
            )
    }

    private fun imageEntryName(index: Int, item: CardScanDatasetItem, imageFile: File): String {
        val extension = imageFile.extension.takeIf { it.isNotBlank() } ?: "jpg"
        val safeId = item.id.replace(Regex("""[^A-Za-z0-9_-]+"""), "_")
        return "images/${index.toString().padStart(4, '0')}_$safeId.$extension"
    }
}
