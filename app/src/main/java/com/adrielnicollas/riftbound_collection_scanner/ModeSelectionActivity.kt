package com.adrielnicollas.riftbound_collection_scanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adrielnicollas.riftbound_collection_scanner.data.LocalImageCleaner
import com.google.android.material.button.MaterialButton
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModeSelectionActivity : AppCompatActivity() {
    private lateinit var clearLocalImagesButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        clearLocalImagesButton = findViewById(R.id.clearLocalImagesButton)

        findViewById<MaterialButton>(R.id.singleButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.SINGLE))
        }
        findViewById<MaterialButton>(R.id.bulkButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.BULK))
        }
        findViewById<MaterialButton>(R.id.savedCardsButton).setOnClickListener {
            startActivity(Intent(this, SavedCardsActivity::class.java))
        }
        clearLocalImagesButton.setOnClickListener {
            confirmClearLocalImages()
        }
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
