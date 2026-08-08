package com.adrielnicollas.riftbound_collection_scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import com.adrielnicollas.riftbound_collection_scanner.riot.RiotRiftboundClient
import com.adrielnicollas.riftbound_collection_scanner.ui.CardGuideOverlayView
import com.adrielnicollas.riftbound_collection_scanner.data.AppDatabase
import com.adrielnicollas.riftbound_collection_scanner.data.CardDraftParser
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDates
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDraftEntity
import com.google.android.material.button.MaterialButton
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
import kotlinx.coroutines.withContext

class ScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var bulkCounterText: TextView
    private lateinit var captureButton: MaterialButton
    private lateinit var finishBulkButton: MaterialButton
    private lateinit var cardGuideOverlay: CardGuideOverlayView
    private lateinit var textRecognizer: TextRecognizer

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
        ensureCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
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
                startActivity(CardReviewActivity.intentFor(this, sessionId, mode))
                finish()
            }
        }
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
                    showStatus("Camara pronta")
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
            runTextRecognition(croppedPhotoFile)
        } else {
            rawPhotoFile.copyTo(croppedPhotoFile, overwrite = true)
            rawPhotoFile.delete()
            runTextRecognition(croppedPhotoFile)
        }
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
                        saveDraft(photoFile, result.text)
                    } finally {
                        captureButton.isEnabled = true
                        finishBulkButton.isEnabled = true
                    }
                }
            }
            .addOnFailureListener {
                lifecycleScope.launch {
                    try {
                        saveDraft(photoFile, "")
                    } finally {
                        captureButton.isEnabled = true
                        finishBulkButton.isEnabled = true
                    }
                }
            }
    }

    private suspend fun saveDraft(photoFile: File, ocrText: String) {
        val parsed = CardDraftParser.parse(ocrText)
        showStatus("A confirmar dados da carta...")
        val officialCard = withContext(Dispatchers.IO) {
            riotClient.findBestMatch(parsed)
        }
        val finalPhotoFile = officialCard?.let { card ->
            val officialPhotoFile = createOfficialPhotoFile()
            val downloaded = withContext(Dispatchers.IO) {
                riotClient.downloadCardImage(card, officialPhotoFile)
            }
            if (downloaded) officialPhotoFile else null
        } ?: photoFile
        val scannedAt = ScanDates.now()
        val draft = withContext(Dispatchers.IO) {
            val nextOrder = database.cardDao().getMaxDraftOrder(sessionId) + 1
            val entity = ScanDraftEntity(
                sessionId = sessionId,
                imagePath = finalPhotoFile.absolutePath,
                ocrText = officialCard?.effectText?.takeIf { it.isNotBlank() } ?: parsed.effectText,
                name = officialCard?.name?.takeIf { it.isNotBlank() } ?: parsed.name,
                cardNumber = officialCard?.cardNumber?.takeIf { it.isNotBlank() } ?: parsed.cardNumber,
                cost = parsed.cost,
                cardType = officialCard?.type?.takeIf { it.isNotBlank() } ?: parsed.cardType,
                domain = officialCard?.domain?.takeIf { it.isNotBlank() } ?: parsed.domain,
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

    private fun updateBulkCounter() {
        if (mode == ScanMode.BULK) {
            bulkCounterText.text = getString(R.string.bulk_status, capturedCount)
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

    private fun createOfficialPhotoFile(): File {
        val photosDir = File(filesDir, "card_photos").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(photosDir, "official_riftbound_$timestamp.jpg")
    }

    private fun showStatus(message: String) {
        statusText.text = message
    }

    companion object {
        const val EXTRA_MODE = "extra_scan_mode"
        const val EXTRA_SESSION_ID = "extra_session_id"

        fun intentFor(context: Context, mode: ScanMode): Intent {
            return Intent(context, ScannerActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_SESSION_ID, UUID.randomUUID().toString())
        }
    }
}
