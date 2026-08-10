package com.adrielnicollas.riftbound_collection_scanner

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.adrielnicollas.riftbound_collection_scanner.data.AppDatabase
import com.adrielnicollas.riftbound_collection_scanner.data.CardScanDatasetExporter
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDraftEntity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BulkReviewActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var draftList: LinearLayout
    private lateinit var exportButton: MaterialButton

    private val database by lazy { AppDatabase.get(this) }
    private lateinit var sessionId: String
    private var drafts: List<ScanDraftEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_review)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        title = findViewById(R.id.bulkReviewTitle)
        subtitle = findViewById(R.id.bulkReviewSubtitle)
        draftList = findViewById(R.id.bulkDraftList)
        exportButton = findViewById(R.id.exportBulkDebugButton)
        exportButton.setOnClickListener { exportDataset() }
    }

    override fun onResume() {
        super.onResume()
        loadDrafts()
    }

    private fun loadDrafts() {
        lifecycleScope.launch {
            drafts = withContext(Dispatchers.IO) {
                database.cardDao().getDraftsForSession(sessionId)
            }
            if (drafts.isEmpty()) {
                Toast.makeText(this@BulkReviewActivity, "Sem cartas nesta sessao", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            title.text = getString(R.string.bulk_review_title)
            subtitle.text = resources.getQuantityString(R.plurals.bulk_review_subtitle, drafts.size, drafts.size)
            renderDrafts()
        }
    }

    private fun renderDrafts() {
        draftList.removeAllViews()
        drafts.forEachIndexed { index, draft ->
            val button = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.bulk_review_item_height),
                ).apply {
                    bottomMargin = resources.getDimensionPixelSize(R.dimen.bulk_review_item_gap)
                }
                text = draft.displayLabel(index)
                textSize = 16f
                setTextColor(getColor(R.color.rift_text))
                strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.rift_secondary))
                strokeWidth = resources.getDimensionPixelSize(R.dimen.bulk_review_item_stroke)
                cornerRadius = resources.getDimensionPixelSize(R.dimen.bulk_review_item_radius)
                setOnClickListener {
                    startActivity(CardReviewActivity.intentForDraft(this@BulkReviewActivity, draft.id))
                }
            }
            draftList.addView(button)
        }
    }

    private fun ScanDraftEntity.displayLabel(index: Int): String {
        val number = cardNumber.takeIf { it.isNotBlank() } ?: "#${index + 1}"
        return getString(R.string.bulk_review_item_label, number)
    }

    private fun exportDataset() {
        lifecycleScope.launch {
            exportButton.isEnabled = false
            try {
                val zipFile = withContext(Dispatchers.IO) {
                    val currentDrafts = database.cardDao().getDraftsForSession(sessionId)
                    val items = currentDrafts.map { CardScanDatasetExporter.fromDraft(it) }
                    CardScanDatasetExporter.createExportZip(this@BulkReviewActivity, items)
                }
                if (zipFile == null) {
                    Toast.makeText(this@BulkReviewActivity, R.string.export_ocr_dataset_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val uri = FileProvider.getUriForFile(this@BulkReviewActivity, "$packageName.fileprovider", zipFile)
                val intent = Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(intent, getString(R.string.export_ocr_dataset)))
            } finally {
                exportButton.isEnabled = true
            }
        }
    }

    companion object {
        private const val EXTRA_SESSION_ID = "extra_session_id"

        fun intentFor(context: Context, sessionId: String): Intent {
            return Intent(context, BulkReviewActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }
}
