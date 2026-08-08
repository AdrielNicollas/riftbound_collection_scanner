package com.adrielnicollas.riftbound_collection_scanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SymbolDatasetActivity : AppCompatActivity() {
    private lateinit var labelInput: AutoCompleteTextView
    private lateinit var countText: TextView
    private lateinit var captureButton: MaterialButton
    private lateinit var exportButton: MaterialButton

    private var pendingPhotoFile: File? = null

    private val labels = listOf(
        "Rune",
        "Power",
        "Might",
        "Fury",
        "Calm",
        "Mind",
        "Body",
        "Chaos",
        "Order",
        "Unknown",
    )

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = pendingPhotoFile
        pendingPhotoFile = null
        if (saved && file != null) {
            Toast.makeText(this, "Imagem guardada em ${selectedLabel()}", Toast.LENGTH_SHORT).show()
        } else {
            file?.delete()
            Toast.makeText(this, "Captura cancelada", Toast.LENGTH_SHORT).show()
        }
        updateCount()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_symbol_dataset)

        labelInput = findViewById(R.id.symbolLabelInput)
        countText = findViewById(R.id.symbolCountText)
        captureButton = findViewById(R.id.captureSymbolButton)
        exportButton = findViewById(R.id.exportDatasetButton)

        labelInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        labelInput.setText(labels.first(), false)
        labelInput.setOnClickListener { labelInput.showDropDown() }
        labelInput.setOnItemClickListener { _, _, _, _ -> updateCount() }

        captureButton.setOnClickListener { captureSymbolPhoto() }
        exportButton.setOnClickListener { exportDatasetZip() }

        updateCount()
    }

    private fun captureSymbolPhoto() {
        val label = selectedLabel()
        val file = createPhotoFile(label)
        pendingPhotoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        takePicture.launch(uri)
    }

    private fun exportDatasetZip() {
        lifecycleScope.launch {
            exportButton.isEnabled = false
            captureButton.isEnabled = false
            try {
                val zipFile = withContext(Dispatchers.IO) { createDatasetZip() }
                if (zipFile == null) {
                    Toast.makeText(this@SymbolDatasetActivity, "Ainda nao ha imagens para exportar", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                shareZip(zipFile)
            } finally {
                exportButton.isEnabled = true
                captureButton.isEnabled = true
            }
        }
    }

    private fun shareZip(zipFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        startActivity(Intent.createChooser(intent, "Exportar dataset"))
    }

    private fun selectedLabel(): String {
        return labelInput.text?.toString()?.trim()?.takeIf { it in labels } ?: labels.first()
    }

    private fun updateCount() {
        val label = selectedLabel()
        val labelCount = labelDir(label).listFiles { file -> file.isFile }?.size ?: 0
        val totalCount = labels.sumOf { currentLabel ->
            labelDir(currentLabel).listFiles { file -> file.isFile }?.size ?: 0
        }
        countText.text = "Imagens em $label: $labelCount\nTotal do dataset: $totalCount"
    }

    private fun createPhotoFile(label: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(labelDir(label).apply { mkdirs() }, "${label.lowercase(Locale.US)}_$timestamp.jpg")
    }

    private fun createDatasetZip(): File? {
        val datasetDir = datasetDir()
        val files = labels.flatMap { label ->
            labelDir(label).listFiles { file -> file.isFile }?.toList().orEmpty()
        }
        if (files.isEmpty()) return null

        val exportDir = File(cacheDir, "exports").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(exportDir, "riftbound_symbol_dataset_$timestamp.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            files.forEach { file ->
                val relativePath = file.relativeTo(datasetDir).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relativePath))
                BufferedInputStream(FileInputStream(file)).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }

        return zipFile
    }

    private fun labelDir(label: String): File = File(datasetDir(), label.lowercase(Locale.US))

    private fun datasetDir(): File = File(filesDir, "symbol_dataset")
}
