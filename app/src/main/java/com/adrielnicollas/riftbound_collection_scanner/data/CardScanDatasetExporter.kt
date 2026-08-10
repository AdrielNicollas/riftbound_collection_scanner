package com.adrielnicollas.riftbound_collection_scanner.data

import android.content.Context
import android.graphics.Bitmap
import com.adrielnicollas.riftbound_collection_scanner.imaging.CardImageSignalDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
    val rawOcrText: String = "",
    val effectOcrText: String = "",
    val sectionOcr: Map<String, String> = emptyMap(),
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
            rawOcrText = draft.rawOcrText,
            effectOcrText = draft.effectOcrText,
            sectionOcr = parseSectionOcrJson(draft.sectionOcrJson),
            scannedAt = draft.scannedAt,
            scanDate = draft.scanDate,
        )
    }

    fun parseSectionOcrJson(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            buildMap {
                root.keys().forEach { key ->
                    val value = root.optString(key).trim()
                    if (value.isNotBlank()) put(key, value)
                }
            }
        }.getOrDefault(emptyMap())
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

                val cropEntries = writeSectionCrops(zip, index + 1, item, imageFile)
                jsonItems.put(item.toJson(imageName, cropEntries))
            }

            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(exportReadme().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("scans.json"))
            zip.write(
                JSONObject()
                    .put("version", 2)
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

    private fun CardScanDatasetItem.toJson(imageName: String, cropEntries: Map<String, String>): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("image", imageName)
            .put("sectionCrops", JSONObject(cropEntries))
            .put(
                "ocr",
                JSONObject()
                    .put("raw", rawOcrText)
                    .put("effectCropRaw", effectOcrText)
                    .put("sections", JSONObject(sectionOcr))
                    .put("parsedEffect", effectText),
            )
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

    private fun writeSectionCrops(
        zip: ZipOutputStream,
        index: Int,
        item: CardScanDatasetItem,
        imageFile: File,
    ): Map<String, String> {
        val bitmap = CardImageSignalDetector.decode(imageFile) ?: return emptyMap()
        return try {
            val safeId = item.id.replace(Regex("""[^A-Za-z0-9_-]+"""), "_")
            val prefix = "crops/${index.toString().padStart(4, '0')}_$safeId"
            val crops = listOf(
                "cost" to { source: Bitmap -> CardImageSignalDetector.cropCost(source) },
                "rune_cost_number" to { source: Bitmap -> CardImageSignalDetector.cropRuneCostNumber(source) },
                "power_cost" to { source: Bitmap -> CardImageSignalDetector.cropPowerCostSymbol(source) },
                "might" to { source: Bitmap -> CardImageSignalDetector.cropMight(source) },
                "might_number" to { source: Bitmap -> CardImageSignalDetector.cropMightNumber(source) },
                "domain_detected" to { source: Bitmap -> CardImageSignalDetector.cropDomainSymbol(source) },
                "domain_fixed" to { source: Bitmap -> CardImageSignalDetector.cropDomainSymbolFixed(source) },
                "type_tags" to { source: Bitmap -> CardImageSignalDetector.cropTypeTags(source) },
                "name_band" to { source: Bitmap -> CardImageSignalDetector.cropNameBand(source) },
                "effect_text" to { source: Bitmap -> CardImageSignalDetector.cropEffectText(source) },
                "lore_box" to { source: Bitmap -> CardImageSignalDetector.cropLoreBox(source) },
                "lore_marker" to { source: Bitmap -> CardImageSignalDetector.cropLoreMarker(source) },
                "footer_number" to { source: Bitmap -> CardImageSignalDetector.cropFooterNumber(source) },
            )

            buildMap {
                crops.forEach { (name, cropper) ->
                    val entryName = "$prefix/$name.jpg"
                    val crop = runCatching { cropper(bitmap) }.getOrNull() ?: return@forEach
                    try {
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.write(crop.toJpegBytes())
                        zip.closeEntry()
                        put(name, entryName)
                    } finally {
                        crop.recycle()
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        return ByteArrayOutputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, 94, output)
            output.toByteArray()
        }
    }

    private fun exportReadme(): String {
        return """
            Riftbound OCR dataset export

            images/ contains the full cropped card photos used by the app.
            crops/ contains the same cards split into diagnostic sections:
            cost, rune_cost_number, power_cost, might, might_number, domain_detected,
            domain_fixed, type_tags, name_band, effect_text, lore_box, lore_marker,
            and footer_number.

            scans.json links each card image to its section crops and stores:
            - fields: parsed values shown by the app
            - ocr.raw: full-card OCR text
            - ocr.effectCropRaw: OCR text read only from the effect crop
            - ocr.sections: OCR text read from each individual crop
            - ocr.parsedEffect: final normalized effect text
        """.trimIndent()
    }

    private fun imageEntryName(index: Int, item: CardScanDatasetItem, imageFile: File): String {
        val extension = imageFile.extension.takeIf { it.isNotBlank() } ?: "jpg"
        val safeId = item.id.replace(Regex("""[^A-Za-z0-9_-]+"""), "_")
        return "images/${index.toString().padStart(4, '0')}_$safeId.$extension"
    }
}
