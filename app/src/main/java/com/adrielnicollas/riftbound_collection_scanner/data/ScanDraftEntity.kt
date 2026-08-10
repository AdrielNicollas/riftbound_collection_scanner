package com.adrielnicollas.riftbound_collection_scanner.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scan_drafts",
    indices = [Index(value = ["sessionId", "captureOrder"])],
)
data class ScanDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val imagePath: String,
    val ocrText: String,
    val rawOcrText: String = "",
    val effectOcrText: String = "",
    val sectionOcrJson: String = "{}",
    val name: String,
    val cardNumber: String,
    val cardSet: String = "",
    val cost: Int?,
    val powerCost: String = "",
    val might: Int?,
    val cardType: String,
    val domain: String,
    val scannedAt: Long,
    val scanDate: String,
    val captureOrder: Int,
)
