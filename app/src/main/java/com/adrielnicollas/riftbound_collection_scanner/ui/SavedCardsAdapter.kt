package com.adrielnicollas.riftbound_collection_scanner.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.adrielnicollas.riftbound_collection_scanner.R
import com.adrielnicollas.riftbound_collection_scanner.data.CardEntity
import com.adrielnicollas.riftbound_collection_scanner.data.ScanDates
import java.io.File

class SavedCardsAdapter : RecyclerView.Adapter<SavedCardsAdapter.SavedCardViewHolder>() {
    private val cards = mutableListOf<CardEntity>()

    fun submitList(items: List<CardEntity>) {
        cards.clear()
        cards.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SavedCardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_saved_card, parent, false)
        return SavedCardViewHolder(view)
    }

    override fun onBindViewHolder(holder: SavedCardViewHolder, position: Int) {
        holder.bind(cards[position])
    }

    override fun getItemCount(): Int = cards.size

    class SavedCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.cardThumbnail)
        private val name: TextView = itemView.findViewById(R.id.cardName)
        private val details: TextView = itemView.findViewById(R.id.cardDetails)

        fun bind(card: CardEntity) {
            name.text = card.name
            details.text = buildDetails(card)

            val imageFile = File(card.imagePath)
            if (imageFile.exists()) {
                thumbnail.setImageURI(Uri.fromFile(imageFile))
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_menu_report_image)
            }
        }

        private fun buildDetails(card: CardEntity): String {
            val parts = buildList {
                if (card.cardNumber.isNotBlank()) add("#${card.cardNumber}")
                card.cost?.let { add("Custo $it") }
                if (card.cardType.isNotBlank()) add(card.cardType)
                if (card.domain.isNotBlank()) add(card.domain)
                if (card.scannedAt > 0) add(ScanDates.formatDate(card.scannedAt))
            }
            return if (parts.isEmpty()) "Sem detalhes" else parts.joinToString(" | ")
        }
    }
}
