package com.adrielnicollas.riftbound_collection_scanner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.adrielnicollas.riftbound_collection_scanner.data.AppDatabase
import com.adrielnicollas.riftbound_collection_scanner.data.CardEntity
import com.adrielnicollas.riftbound_collection_scanner.data.CardKeys
import com.adrielnicollas.riftbound_collection_scanner.data.CardDraftParser
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDates
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDraftEntity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CardReviewActivity : AppCompatActivity() {
    private lateinit var reviewTitle: TextView
    private lateinit var reviewContainer: LinearLayout
    private lateinit var saveCardsButton: MaterialButton

    private val database by lazy { AppDatabase.get(this) }
    private val binders = mutableListOf<DraftReviewBinder>()
    private lateinit var sessionId: String
    private lateinit var mode: ScanMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_review)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { ScanMode.valueOf(it) }
            ?: ScanMode.SINGLE

        reviewTitle = findViewById(R.id.reviewTitle)
        reviewContainer = findViewById(R.id.reviewContainer)
        saveCardsButton = findViewById(R.id.saveCardsButton)
        saveCardsButton.text = if (mode == ScanMode.SINGLE) getString(R.string.save_card) else getString(R.string.save_cards)
        saveCardsButton.setOnClickListener { saveReviewedCards() }

        loadDrafts()
    }

    private fun loadDrafts() {
        lifecycleScope.launch {
            val drafts = withContext(Dispatchers.IO) {
                database.cardDao().getDraftsForSession(sessionId)
            }
            if (drafts.isEmpty()) {
                Toast.makeText(this@CardReviewActivity, "Sem cartas para rever", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            reviewTitle.text = if (drafts.size == 1) "Rever carta" else "Rever cartas (${drafts.size})"
            renderDrafts(drafts)
        }
    }

    private fun renderDrafts(drafts: List<ScanDraftEntity>) {
        reviewContainer.removeAllViews()
        binders.clear()
        val inflater = LayoutInflater.from(this)
        drafts.forEachIndexed { index, draft ->
            val view = inflater.inflate(R.layout.item_review_draft, reviewContainer, false)
            val binder = DraftReviewBinder(view, draft, index + 1)
            binder.bind()
            reviewContainer.addView(view)
            binders.add(binder)
        }
    }

    private fun saveReviewedCards() {
        val invalidBinder = binders.firstOrNull { it.name.isBlank() }
        if (invalidBinder != null) {
            invalidBinder.nameLayout.error = "Obrigatorio"
            Toast.makeText(this, "Indica o nome de todas as cartas", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val cards = binders.map { binder ->
                val key = CardKeys.build(binder.name, binder.cardNumber)
                CardEntity(
                    name = binder.name,
                    cardNumber = binder.cardNumber,
                    cost = binder.cost,
                    cardType = binder.cardType,
                    domain = binder.domain,
                    imagePath = binder.draft.imagePath,
                    ocrText = binder.ocrText,
                    scannedAt = binder.draft.scannedAt,
                    scanDate = binder.draft.scanDate,
                    cardKey = key,
                )
            }

            withContext(Dispatchers.IO) {
                cards.forEach { database.cardDao().insertCard(it) }
                database.cardDao().deleteDraftsForSession(sessionId)
            }

            Toast.makeText(this@CardReviewActivity, "Cartas guardadas", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this@CardReviewActivity, SavedCardsActivity::class.java))
            finish()
        }
    }

    private inner class DraftReviewBinder(
        private val root: View,
        val draft: ScanDraftEntity,
        private val position: Int,
    ) {
        val nameLayout: TextInputLayout = root.findViewById(R.id.nameLayout)
        private val draftTitle: TextView = root.findViewById(R.id.draftTitle)
        private val draftImage: ImageView = root.findViewById(R.id.draftImage)
        private val nameInput: TextInputEditText = root.findViewById(R.id.nameInput)
        private val cardNumberInput: TextInputEditText = root.findViewById(R.id.cardNumberInput)
        private val costInput: TextInputEditText = root.findViewById(R.id.costInput)
        private val typeInput: AutoCompleteTextView = root.findViewById(R.id.typeInput)
        private val domainInput: AutoCompleteTextView = root.findViewById(R.id.domainInput)
        private val scanDateText: TextView = root.findViewById(R.id.scanDateText)
        private val ocrInput: TextInputEditText = root.findViewById(R.id.ocrInput)

        val name: String get() = nameInput.text?.toString()?.trim().orEmpty()
        val cardNumber: String get() = cardNumberInput.text?.toString()?.trim().orEmpty()
        val cost: Int? get() = costInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val cardType: String get() = typeInput.text?.toString()?.trim().orEmpty()
        val domain: String get() = domainInput.text?.toString()?.trim().orEmpty()
        val ocrText: String get() = ocrInput.text?.toString()?.trim().orEmpty()

        fun bind() {
            draftTitle.text = "Carta $position"
            val imageFile = File(draft.imagePath)
            if (imageFile.exists()) draftImage.setImageURI(Uri.fromFile(imageFile))
            nameInput.setText(draft.name)
            cardNumberInput.setText(draft.cardNumber)
            draft.cost?.let { costInput.setText(it.toString()) }
            typeInput.setText(draft.cardType, false)
            domainInput.setText(draft.domain, false)
            ocrInput.setText(draft.ocrText)
            scanDateText.text = "${getString(R.string.scan_date)}: ${ScanDates.formatDateTime(draft.scannedAt)}"
            typeInput.setAdapter(
                ArrayAdapter(this@CardReviewActivity, android.R.layout.simple_dropdown_item_1line, CardDraftParser.cardTypes),
            )
            domainInput.setAdapter(
                ArrayAdapter(this@CardReviewActivity, android.R.layout.simple_dropdown_item_1line, CardDraftParser.domains),
            )
            typeInput.setOnClickListener { typeInput.showDropDown() }
            domainInput.setOnClickListener { domainInput.showDropDown() }
        }
    }

    companion object {
        private const val EXTRA_SESSION_ID = "extra_session_id"
        private const val EXTRA_MODE = "extra_mode"

        fun intentFor(context: Context, sessionId: String, mode: ScanMode): Intent {
            return Intent(context, CardReviewActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_MODE, mode.name)
        }
    }
}
