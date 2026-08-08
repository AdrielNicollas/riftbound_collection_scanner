package com.adrielnicollas.riftbound_collection_scanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class ModeSelectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        findViewById<MaterialButton>(R.id.singleButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.SINGLE))
        }
        findViewById<MaterialButton>(R.id.bulkButton).setOnClickListener {
            startActivity(ScannerActivity.intentFor(this, ScanMode.BULK))
        }
        findViewById<MaterialButton>(R.id.savedCardsButton).setOnClickListener {
            startActivity(Intent(this, SavedCardsActivity::class.java))
        }
    }
}
