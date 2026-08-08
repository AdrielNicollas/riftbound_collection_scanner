package com.adrielnicollas.riftbound_collection_scanner

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adrielnicollas.riftbound_collection_scanner.data.AppDatabase
import com.adrielnicollas.riftbound_collection_scanner.ui.SavedCardsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SavedCardsActivity : AppCompatActivity() {
    private val database by lazy { AppDatabase.get(this) }
    private val adapter = SavedCardsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_cards)

        val list = findViewById<RecyclerView>(R.id.savedCardsList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        loadCards()
    }

    private fun loadCards() {
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                database.cardDao().getCards()
            }
            adapter.submitList(cards)
            findViewById<TextView>(R.id.savedCardsTitle).text = "${getString(R.string.saved_cards)} (${cards.size})"
        }
    }
}
