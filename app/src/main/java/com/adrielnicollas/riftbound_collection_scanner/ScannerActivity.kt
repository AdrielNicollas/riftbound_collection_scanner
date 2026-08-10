package com.adrielnicollas.riftbound_collection_scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.adrielnicollas.riftbound_collection_scanner.imaging.CardImageCropper
import com.adrielnicollas.riftbound_collection_scanner.imaging.CardFramingValidator
import com.adrielnicollas.riftbound_collection_scanner.imaging.CardImageSignalDetector
import com.adrielnicollas.riftbound_collection_scanner.imaging.CardImageSignals
import com.adrielnicollas.riftbound_collection_scanner.riot.RiotRiftboundClient
import com.adrielnicollas.riftbound_collection_scanner.ui.CardGuideOverlayView
import com.adrielnicollas.riftbound_collection_scanner.data.AppDatabase
import com.adrielnicollas.riftbound_collection_scanner.data.CardDraftParser
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDates
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDraftEntity
import com.google.android.material.button.MaterialButton
import com.adrielnicollas.riftbound_collection_scanner.imaging.DomainSymbolClassifier
import com.adrielnicollas.riftbound_collection_scanner.imaging.SymbolClassifier
import com.adrielnicollas.riftbound_collection_scanner.imaging.SymbolPrediction
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class ScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var bulkCounterText: TextView
    private lateinit var captureButton: MaterialButton
    private lateinit var finishBulkButton: MaterialButton
    private lateinit var cardGuideOverlay: CardGuideOverlayView
    private lateinit var textRecognizer: TextRecognizer
    private val domainClassifierLazy = lazy { DomainSymbolClassifier(this) }
    private val symbolClassifierLazy = lazy { SymbolClassifier(this) }
    private val domainClassifier by domainClassifierLazy
    private val symbolClassifier by symbolClassifierLazy

    private val database by lazy { AppDatabase.get(this) }
    private val riotClient by lazy { RiotRiftboundClient() }
    private lateinit var mode: ScanMode
    private lateinit var sessionId: String
    private var imageCapture: ImageCapture? = null
    private var capturedCount = 0

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showStatus("Permissao da camara negada")
            captureButton.isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { ScanMode.valueOf(it) }
            ?: ScanMode.SINGLE
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        bindViews()
        setupModeUi()
        setupActions()
        loadBulkProgress()
        ensureCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
        if (domainClassifierLazy.isInitialized()) {
            domainClassifier.close()
        }
        if (symbolClassifierLazy.isInitialized()) {
            symbolClassifier.close()
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        bulkCounterText = findViewById(R.id.bulkCounterText)
        captureButton = findViewById(R.id.captureButton)
        finishBulkButton = findViewById(R.id.finishBulkButton)
        cardGuideOverlay = findViewById(R.id.cardGuideOverlay)
    }

    private fun setupModeUi() {
        val isBulk = mode == ScanMode.BULK
        bulkCounterText.isVisible = isBulk
        finishBulkButton.isVisible = isBulk
        updateBulkCounter()
    }

    private fun setupActions() {
        captureButton.setOnClickListener { takePhoto() }
        finishBulkButton.setOnClickListener {
            if (capturedCount == 0) {
                showStatus("Captura pelo menos uma carta antes de terminar.")
            } else {
                confirmFinishBulk()
            }
        }
    }

    private fun confirmFinishBulk() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bulk_finish_confirm_title)
            .setMessage(getString(R.string.bulk_finish_confirm_message, capturedCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.bulk_finish_confirm_action) { _, _ ->
                startActivity(BulkReviewActivity.intentFor(this, sessionId))
                finish()
            }
            .show()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    showStatus(cameraReadyStatus())
                } catch (exception: Exception) {
                    showStatus("Nao foi possivel iniciar a camara")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            showStatus("Camara ainda nao esta pronta")
            return
        }

        val rawPhotoFile = createRawPhotoFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(rawPhotoFile).build()

        captureButton.isEnabled = false
        finishBulkButton.isEnabled = false
        showStatus("A capturar imagem...")

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch { processCapturedPhoto(rawPhotoFile) }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureButton.isEnabled = true
                    finishBulkButton.isEnabled = true
                    showStatus("Falha ao capturar imagem")
                }
            },
        )
    }

    private suspend fun processCapturedPhoto(rawPhotoFile: File) {
        showStatus("A recortar carta pela mira...")
        val croppedPhotoFile = createPhotoFile()
        val cropped = withContext(Dispatchers.IO) {
            CardImageCropper.cropToGuide(
                sourceFile = rawPhotoFile,
                destinationFile = croppedPhotoFile,
                previewSize = Size(previewView.width, previewView.height),
                guideRect = cardGuideOverlay.guideRect(),
            )
        }
        if (cropped) {
            rawPhotoFile.delete()
        } else {
            rawPhotoFile.delete()
            croppedPhotoFile.delete()
            captureButton.isEnabled = true
            finishBulkButton.isEnabled = true
            showStatus("Nao consegui recortar pela mira. Tenta novamente.")
            return
        }

        val framingResult = withContext(Dispatchers.IO) {
            CardFramingValidator.validate(croppedPhotoFile)
        }
        if (!framingResult.isAcceptable) {
            croppedPhotoFile.delete()
            captureButton.isEnabled = true
            finishBulkButton.isEnabled = true
            showStatus(framingResult.message)
            return
        }

        runTextRecognition(croppedPhotoFile)
    }

    private fun runTextRecognition(photoFile: File) {
        val image = try {
            InputImage.fromFilePath(this, Uri.fromFile(photoFile))
        } catch (exception: IOException) {
            captureButton.isEnabled = true
            finishBulkButton.isEnabled = true
            showStatus("Foto capturada, mas o OCR nao conseguiu abrir a imagem")
            return
        }

        showStatus("A ler texto da carta...")
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                lifecycleScope.launch {
                    try {
                        saveDraft(
                            photoFile = photoFile,
                            ocrText = result.text,
                            sectionOcr = recognizeSectionOcr(photoFile),
                            imageSignals = detectImageSignals(photoFile),
                        )
                    } finally {
                        captureButton.isEnabled = true
                        finishBulkButton.isEnabled = true
                    }
                }
            }
            .addOnFailureListener {
                lifecycleScope.launch {
                    try {
                        saveDraft(
                            photoFile = photoFile,
                            ocrText = "",
                            sectionOcr = recognizeSectionOcr(photoFile),
                            imageSignals = detectImageSignals(photoFile),
                        )
                    } finally {
                        captureButton.isEnabled = true
                        finishBulkButton.isEnabled = true
                    }
                }
            }
    }

    private suspend fun recognizeSectionOcr(photoFile: File): Map<String, String> {
        val crops = withContext(Dispatchers.IO) {
            val bitmap = CardImageSignalDetector.decode(photoFile) ?: return@withContext null
            try {
                listOf(
                    "cost" to CardImageSignalDetector.cropCost(bitmap),
                    "rune_cost_number" to CardImageSignalDetector.cropRuneCostNumber(bitmap),
                    "might_number" to CardImageSignalDetector.cropMightNumber(bitmap),
                    "type_tags" to CardImageSignalDetector.cropTypeTags(bitmap),
                    "name_band" to CardImageSignalDetector.cropNameBand(bitmap),
                    "effect_text" to CardImageSignalDetector.cropEffectText(bitmap),
                    "lore_box" to CardImageSignalDetector.cropLoreBox(bitmap),
                    "footer_number" to CardImageSignalDetector.cropFooterNumber(bitmap),
                )
            } finally {
                bitmap.recycle()
            }
        } ?: return emptyMap()

        return try {
            buildMap {
                crops.forEach { (name, crop) ->
                    val text = recognizeText(crop).trim()
                    if (text.isNotBlank()) put(name, text)
                }
            }
        } finally {
            crops.forEach { (_, crop) -> crop.recycle() }
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume("")
                }
        }
    }

    private suspend fun detectImageSignals(photoFile: File): CardImageSignals {
        val bitmap = withContext(Dispatchers.IO) {
            CardImageSignalDetector.decode(photoFile)
        } ?: return CardImageSignals()

        return try {
            val runeCostBitmap = CardImageSignalDetector.cropRuneCostNumber(bitmap)
            val costBitmap = CardImageSignalDetector.cropCost(bitmap)
            val powerCostBitmap = CardImageSignalDetector.cropPowerCostSymbol(bitmap)
            val mightNumberBitmap = CardImageSignalDetector.cropMightNumber(bitmap)
            val mightBitmap = CardImageSignalDetector.cropMight(bitmap)
            val domainBitmap = CardImageSignalDetector.cropDomainSymbol(bitmap)
            try {
                val focusedCost = recognizeFocusedNumber(runeCostBitmap) ?: recognizeFocusedNumber(costBitmap)
                val focusedMight = recognizeFocusedNumber(mightNumberBitmap, repairLeadingIconOne = true)
                    ?: recognizeFocusedNumber(mightBitmap, repairLeadingIconOne = true)
                val predictedPowerSymbol = withContext(Dispatchers.IO) {
                    runCatching { symbolClassifier.classify(powerCostBitmap) }.getOrNull()
                }
                val colorDetectedPowerCost = CardImageSignalDetector.detectPowerCostByColor(powerCostBitmap)
                val predictedPowerCost = predictedPowerSymbol.toPowerCost(colorDetectedPowerCost)
                val predictedDomain = withContext(Dispatchers.IO) {
                    runCatching { domainClassifier.classify(domainBitmap) }.getOrNull()
                }
                val colorDetectedDomain = CardImageSignalDetector.detectDomainByColor(domainBitmap)
                val acceptedPredictedDomain = predictedDomain
                    ?.takeIf { prediction -> prediction.confidence >= MIN_DOMAIN_CONFIDENCE }
                    ?.domain
                    .orEmpty()
                val finalDomain = colorDetectedDomain.takeIf { it.isNotBlank() }
                    ?: acceptedPredictedDomain
                CardImageSignals(
                    cost = focusedCost,
                    powerCost = predictedPowerCost,
                    might = focusedMight,
                    domain = finalDomain,
                )
            } finally {
                runeCostBitmap.recycle()
                costBitmap.recycle()
                powerCostBitmap.recycle()
                mightNumberBitmap.recycle()
                mightBitmap.recycle()
                domainBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognizeFocusedNumber(bitmap: Bitmap, repairLeadingIconOne: Boolean = false): Int? {
        val text = suspendCancellableCoroutine { continuation ->
            textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.text)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume("")
                }
        }

        val digits = Regex("""\b\d{1,2}\b""")
            .find(text)
            ?.value
            ?: return null
        val value = digits.toIntOrNull() ?: return null
        if (repairLeadingIconOne && digits.length == 2 && value > 12) {
            return digits.last().digitToIntOrNull()
        }
        return value.takeIf { it in 0..99 }
    }

    private fun SymbolPrediction?.toPowerCost(colorDetectedPowerCost: String): String {
        if (colorDetectedPowerCost.isNotBlank()) return colorDetectedPowerCost
        if (this == null) return colorDetectedPowerCost
        if (label == "power_any" && confidence >= MIN_POWER_COST_CONFIDENCE) return "1 Any"
        return ""
    }

    private fun chooseName(officialName: String, segmentedName: String, fullName: String): String {
        officialName.takeIf { it.isNotBlank() }?.let { return it }
        val segmented = segmentedName.takeIf { it.isReliableCardName() }
        val full = fullName.takeIf { it.isReliableCardName() }
        if (segmented != null && full != null && full.isMoreCompleteVersionOf(segmented)) {
            return full
        }
        if (full != null && (segmented == null || (!segmented.hasLowercaseLetter() && full.hasLowercaseLetter()))) {
            return full
        }
        return segmented ?: full ?: segmentedName.takeIf { it.isNotBlank() } ?: fullName
    }

    private fun String.isReliableCardName(): Boolean {
        val trimmed = trim()
        if (trimmed.isBlank()) return false
        if (trimmed.any { it.isDigit() }) return false
        val comparable = trimmed.lowercase(Locale.US)
        if (comparable.contains("champ") || comparable.contains(" unit") || comparable.contains("spell")) return false
        val words = comparable.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.any { it in setOf("uni", "uii", "piun", "inmia", "nokus", "nuxus") }) return false
        return true
    }

    private fun String.hasLowercaseLetter(): Boolean = any { it.isLowerCase() }

    private fun String.isMoreCompleteVersionOf(other: String): Boolean {
        val thisParts = splitNameParts()
        val otherParts = other.splitNameParts()
        if (thisParts.first != otherParts.first) return false
        val thisSubtitle = thisParts.second
        val otherSubtitle = otherParts.second
        return thisSubtitle.length >= otherSubtitle.length + 2 &&
            (thisSubtitle.endsWith(otherSubtitle) || thisSubtitle.contains(otherSubtitle))
    }

    private fun String.splitNameParts(): Pair<String, String> {
        val parts = split(Regex("""\s+-\s+"""), limit = 2)
        val base = parts.firstOrNull().orEmpty().cleanNameComparable()
        val subtitle = parts.getOrNull(1).orEmpty().cleanNameComparable()
        return base to subtitle
    }

    private fun String.cleanNameComparable(): String {
        return lowercase(Locale.US).replace(Regex("""[^a-z0-9]+"""), "")
    }

    private fun String.isMightBearingType(): Boolean {
        return lowercase(Locale.US).contains("unit")
    }

    private fun repairEffectFromFullOcr(croppedEffectText: String, fullEffectText: String): String {
        val fullCostNumber = Regex(
            """(?i)\bcosts?\s+no\s+more\s+than\s+(\d{1,2})(?:\[Rune])?[,\s]+ignoring\b""",
        ).find(fullEffectText)
            ?.groupValues
            ?.getOrNull(1)
            ?: return croppedEffectText

        return croppedEffectText.replace(
            Regex("""(?i)\bcosts?\s+no\s+more\s+than\s+\.\s+ignoring\b"""),
        ) { match ->
            val prefix = match.value.substringBefore(".").trimEnd()
            "$prefix $fullCostNumber[Rune], ignoring"
        }
    }

    private fun chooseEffectText(croppedEffectText: String, fullEffectText: String): String {
        val cropped = croppedEffectText.trim()
        val full = fullEffectText.trim()
        if (cropped.isBlank()) return full
        if (full.isBlank()) return cropped

        val croppedScore = cropped.effectQualityScore()
        val fullScore = full.effectQualityScore()
        val fullIsMuchLonger = full.length >= cropped.length + 45 || full.lineCount() >= cropped.lineCount() + 2
        val croppedLooksNoisy = cropped.contains('|') ||
            Regex("""(?i)\b(?:eouip|irinity|vo|nt)\b""").containsMatchIn(cropped)

        return if ((fullIsMuchLonger && fullScore >= croppedScore - 1) || (croppedLooksNoisy && fullScore >= croppedScore)) {
            full
        } else {
            cropped
        }
    }

    private fun String.effectQualityScore(): Int {
        val lower = lowercase(Locale.US)
        val effectWords = listOf(
            "action",
            "attach",
            "choose",
            "draw",
            "equip",
            "give",
            "hidden",
            "reaction",
            "score",
            "tank",
            "when",
        )
        val wordScore = effectWords.count { lower.contains(it) } * 2
        val lengthScore = (length / 35).coerceAtMost(8)
        val noisePenalty = count { it == '|' || it == '<' || it == '>' } +
            Regex("""(?i)\b(?:eouip|irinity|\w{1,2})\b""").findAll(this).count().coerceAtMost(4)
        return wordScore + lengthScore - noisePenalty
    }

    private fun String.lineCount(): Int {
        return lineSequence().count { it.isNotBlank() }
    }

    private suspend fun saveDraft(
        photoFile: File,
        ocrText: String,
        sectionOcr: Map<String, String>,
        imageSignals: CardImageSignals,
    ) {
        val segmentedOcrText = buildSegmentedOcrText(sectionOcr)
        val parsed = CardDraftParser.parse(segmentedOcrText.ifBlank { ocrText })
        val fullParsed = CardDraftParser.parse(ocrText)
        val effectOcrText = sectionOcr["effect_text"].orEmpty()
        val parsedCost = imageSignals.cost ?: parsed.cost ?: fullParsed.cost
        val rawParsedMight = imageSignals.might ?: parsed.might ?: fullParsed.might
        val parsedPowerCost = imageSignals.powerCost.takeIf { it.isNotBlank() }
            ?: parsed.powerCost.takeIf { it.isNotBlank() }
            ?: fullParsed.powerCost

        val croppedEffectText = CardDraftParser.parseEffectText(
            rawText = effectOcrText,
            name = parsed.name,
            cardNumber = parsed.cardNumber,
            cost = parsedCost,
            might = rawParsedMight,
            cardType = parsed.cardType,
            domain = parsed.domain,
        )
        val repairedCroppedEffectText = repairEffectFromFullOcr(
            croppedEffectText = croppedEffectText.takeIf { it.isNotBlank() } ?: parsed.effectText,
            fullEffectText = fullParsed.effectText,
        )
        val effectText = chooseEffectText(
            croppedEffectText = repairedCroppedEffectText,
            fullEffectText = fullParsed.effectText,
        )
        showStatus("A confirmar dados da carta...")
        val officialCard = withContext(Dispatchers.IO) {
            riotClient.findBestMatch(parsed)
        }
        val finalName = chooseName(
            officialName = officialCard?.name.orEmpty(),
            segmentedName = parsed.name,
            fullName = fullParsed.name,
        )
        val finalCardType = officialCard?.type?.takeIf { it.isNotBlank() }
            ?: parsed.cardType.takeIf { it.isNotBlank() }
            ?: fullParsed.cardType
        val parsedMight = rawParsedMight.takeIf { finalCardType.isMightBearingType() }
        val scannedAt = ScanDates.now()
        val draft = withContext(Dispatchers.IO) {
            val nextOrder = database.cardDao().getMaxDraftOrder(sessionId) + 1
            val entity = ScanDraftEntity(
                sessionId = sessionId,
                imagePath = photoFile.absolutePath,
                ocrText = effectText,
                rawOcrText = ocrText,
                effectOcrText = effectOcrText,
                sectionOcrJson = sectionOcr.toJsonString(),
                name = finalName,
                cardNumber = officialCard?.cardNumber?.takeIf { it.isNotBlank() } ?: parsed.cardNumber.takeIf { it.isNotBlank() } ?: fullParsed.cardNumber,
                cardSet = parsed.cardSet.takeIf { it.isNotBlank() } ?: fullParsed.cardSet,
                cost = parsedCost,
                powerCost = parsedPowerCost,
                might = parsedMight,
                cardType = finalCardType,
                domain = officialCard?.domain?.takeIf { it.isNotBlank() }
                    ?: imageSignals.domain.takeIf { it.isNotBlank() }
                    ?: parsed.domain.takeIf { it.isNotBlank() }
                    ?: fullParsed.domain,
                scannedAt = scannedAt,
                scanDate = ScanDates.formatDate(scannedAt),
                captureOrder = nextOrder,
            )
            entity.copy(id = database.cardDao().insertDraft(entity))
        }

        capturedCount += 1
        updateBulkCounter()
        if (mode == ScanMode.SINGLE) {
            startActivity(CardReviewActivity.intentFor(this, draft.sessionId, mode))
            finish()
        } else {
            showStatus("Carta lida. Podes capturar a proxima.")
        }
    }

    private fun buildSegmentedOcrText(sectionOcr: Map<String, String>): String {
        return listOf(
            sectionOcr["rune_cost_number"],
            sectionOcr["might_number"],
            sectionOcr["type_tags"],
            sectionOcr["name_band"],
            sectionOcr["effect_text"],
            sectionOcr["footer_number"],
        )
            .orEmptyText()
    }

    private fun List<String?>.orEmptyText(): String {
        return mapNotNull { it?.trim()?.takeIf { value -> value.isNotBlank() } }
            .joinToString(separator = "\n")
    }

    private fun Map<String, String>.toJsonString(): String {
        return JSONObject().apply {
            entries.sortedBy { it.key }.forEach { (key, value) ->
                put(key, value)
            }
        }.toString()
    }

    private fun updateBulkCounter() {
        if (mode == ScanMode.BULK) {
            bulkCounterText.text = getString(R.string.bulk_status, capturedCount)
        }
    }

    private fun loadBulkProgress() {
        if (mode != ScanMode.BULK) return

        lifecycleScope.launch {
            capturedCount = withContext(Dispatchers.IO) {
                database.cardDao().countDraftsForSession(sessionId)
            }
            updateBulkCounter()
            if (capturedCount > 0) {
                showStatus(getString(R.string.bulk_resumed_status, capturedCount))
            }
        }
    }

    private fun cameraReadyStatus(): String {
        return if (mode == ScanMode.BULK && capturedCount > 0) {
            getString(R.string.bulk_resumed_status, capturedCount)
        } else {
            getString(R.string.status_ready)
        }
    }

    private fun createRawPhotoFile(): File {
        val photosDir = File(cacheDir, "raw_card_photos").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(photosDir, "raw_riftbound_$timestamp.jpg")
    }

    private fun createPhotoFile(): File {
        val photosDir = File(filesDir, "card_photos").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(photosDir, "riftbound_$timestamp.jpg")
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    companion object {
        const val EXTRA_MODE = "extra_scan_mode"
        const val EXTRA_SESSION_ID = "extra_session_id"
        private const val MIN_DOMAIN_CONFIDENCE = 0.85f
        private const val MIN_POWER_COST_CONFIDENCE = 0.90f

        fun intentFor(context: Context, mode: ScanMode): Intent {
            return intentFor(context, mode, UUID.randomUUID().toString())
        }

        fun intentFor(context: Context, mode: ScanMode, sessionId: String): Intent {
            return Intent(context, ScannerActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }
}
