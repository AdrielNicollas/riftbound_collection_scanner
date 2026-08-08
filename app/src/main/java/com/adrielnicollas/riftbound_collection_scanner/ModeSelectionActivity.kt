package com.adrielnicollas.riftbound_collection_scanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.adrielnicollas.riftbound_collection_scanner.imaging.DomainFeedbackStore
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModeSelectionActivity : AppCompatActivity() {
    private lateinit var exportDomainFeedbackButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        exportDomainFeedbackButton = findViewById(R.id.exportDomainFeedbackButton)

        findViewById<MaterialButton>(R.id.singleButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.SINGLE))
        }
        findViewById<MaterialButton>(R.id.bulkButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.BULK))
        }
        findViewById<MaterialButton>(R.id.savedCardsButton).setOnClickListener {
            startActivity(Intent(this, SavedCardsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.symbolDatasetButton).setOnClickListener {
            startActivity(Intent(this, SymbolDatasetActivity::class.java))
        }
        exportDomainFeedbackButton.setOnClickListener {
            exportDomainFeedback()
        }
    }

    private fun exportDomainFeedback() {
        lifecycleScope.launch {
            exportDomainFeedbackButton.isEnabled = false
            try {
                val zipFile = withContext(Dispatchers.IO) {
                    DomainFeedbackStore.createExportZip(this@ModeSelectionActivity)
                }
                if (zipFile == null) {
                    Toast.makeText(
                        this@ModeSelectionActivity,
                        R.string.no_domain_feedback,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }

                val uri = FileProvider.getUriForFile(this@ModeSelectionActivity, "$packageName.fileprovider", zipFile)
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, getString(R.string.export_domain_feedback)))
            } finally {
                exportDomainFeedbackButton.isEnabled = true
            }
        }
    }
}
