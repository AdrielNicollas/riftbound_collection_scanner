package com.adrielnicollas.riftbound_collection_scanner.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CardDao {
    @Insert
    suspend fun insertCard(card: CardEntity): Long

    @Query("SELECT * FROM cards ORDER BY createdAt DESC")
    suspend fun getCards(): List<CardEntity>

    @Insert
    suspend fun insertDraft(draft: ScanDraftEntity): Long

    @Update
    suspend fun updateDraft(draft: ScanDraftEntity)

    @Query("SELECT * FROM scan_drafts WHERE sessionId = :sessionId ORDER BY captureOrder ASC")
    suspend fun getDraftsForSession(sessionId: String): List<ScanDraftEntity>

    @Query("SELECT * FROM scan_drafts WHERE id = :draftId LIMIT 1")
    suspend fun getDraftById(draftId: Long): ScanDraftEntity?

    @Query(
        """
        SELECT
            sessionId,
            COUNT(*) AS draftCount,
            MAX(scannedAt) AS lastScannedAt
        FROM scan_drafts
        GROUP BY sessionId
        ORDER BY lastScannedAt DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestDraftSession(): ScanDraftSessionSummary?

    @Query("SELECT COUNT(*) FROM scan_drafts WHERE sessionId = :sessionId")
    suspend fun countDraftsForSession(sessionId: String): Int

    @Query("SELECT COALESCE(MAX(captureOrder), 0) FROM scan_drafts WHERE sessionId = :sessionId")
    suspend fun getMaxDraftOrder(sessionId: String): Int

    @Query("DELETE FROM scan_drafts WHERE sessionId = :sessionId")
    suspend fun deleteDraftsForSession(sessionId: String)

}

data class ScanDraftSessionSummary(
    val sessionId: String,
    val draftCount: Int,
    val lastScannedAt: Long,
)
