package com.adrielnicollas.riftbound_collection_scanner.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val cardNumber: String,
    val cardSet: String = "",
    val cost: Int?,
    val powerCost: String = "",
    val might: Int?,
    val cardType: String,
    val domain: String,
    val imagePath: String,
    val ocrText: String,
    val scannedAt: Long = System.currentTimeMillis(),
    val scanDate: String = ScanDates.formatDate(scannedAt),
    val cardKey: String = CardKeys.build(name, cardNumber),
    val createdAt: Long = System.currentTimeMillis(),
)
