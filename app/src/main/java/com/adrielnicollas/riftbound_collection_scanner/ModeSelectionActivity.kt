package com.adrielnicollas.riftbound_collection_scanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.adrielnicollas.riftbound_collection_scanner.data.AppDatabase
import com.adrielnicollas.riftbound_collection_scanner.data.LocalImageCleaner
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDraftSessionSummary
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModeSelectionActivity : AppCompatActivity() {
    private lateinit var resumeBulkButton: MaterialButton
    private lateinit var clearLocalImagesButton: MaterialButton

    private val database by lazy { AppDatabase.get(this) }
    private var latestDraftSession: ScanDraftSessionSummary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        resumeBulkButton = findViewById(R.id.resumeBulkButton)
        clearLocalImagesButton = findViewById(R.id.clearLocalImagesButton)

        findViewById<MaterialButton>(R.id.singleButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.SINGLE))
        }
        findViewById<MaterialButton>(R.id.bulkButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.BULK))
        }
        resumeBulkButton.setOnClickListener {
            resumeLatestBulkSession()
        }
        findViewById<MaterialButton>(R.id.savedCardsButton).setOnClickListener {
            startActivity(Intent(this, SavedCardsActivity::class.java))
        }
        clearLocalImagesButton.setOnClickListener {
            confirmClearLocalImages()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshResumeBulkButton()
    }

    private fun refreshResumeBulkButton() {
        lifecycleScope.launch {
            val latestSession = withContext(Dispatchers.IO) {
                database.cardDao().getLatestDraftSession()
            }
            latestDraftSession = latestSession
            resumeBulkButton.isVisible = latestSession != null
            if (latestSession != null) {
                resumeBulkButton.text = resources.getQuantityString(
                    R.plurals.resume_bulk_mode,
                    latestSession.draftCount,
                    latestSession.draftCount,
                )
            }
        }
    }

    private fun resumeLatestBulkSession() {
        val session = latestDraftSession ?: return
        startActivity(ScannerActivity.intentFor(this, ScanMode.BULK, session.sessionId))
    }

    private fun confirmClearLocalImages() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_local_images)
            .setMessage(R.string.clear_local_images_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_local_images_confirm) { _, _ ->
                clearLocalImages()
            }
            .show()
    }

    private fun clearLocalImages() {
        lifecycleScope.launch {
            clearLocalImagesButton.isEnabled = false
            try {
                val result = withContext(Dispatchers.IO) {
                    LocalImageCleaner.clear(this@ModeSelectionActivity)
                }
                Toast.makeText(
                    this@ModeSelectionActivity,
                    getString(R.string.clear_local_images_done, result.deletedFiles, result.deletedMegabytes),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                clearLocalImagesButton.isEnabled = true
            }
        }
    }
}
