package com.adrielnicollas.riftbound_collection_scanner.data

import android.content.Context
import java.io.File
import java.util.Locale

data class LocalImageCleanupResult(
    val deletedFiles: Int,
    val deletedBytes: Long,
) {
    val deletedMegabytes: String
        get() = String.format(Locale.US, "%.1f MB", deletedBytes / (1024.0 * 1024.0))
}

object LocalImageCleaner {
    fun clear(context: Context): LocalImageCleanupResult {
        val targets = listOf(
            File(context.filesDir, "card_photos"),
            File(context.filesDir, "domain_feedback"),
            File(context.filesDir, "symbol_dataset"),
            File(context.cacheDir, "raw_card_photos"),
            File(context.cacheDir, "exports"),
        )

        var deletedFiles = 0
        var deletedBytes = 0L

        targets.forEach { target ->
            if (!target.exists()) return@forEach
            target.walkBottomUp().forEach { file ->
                if (file.isFile) {
                    val size = file.length()
                    if (file.delete()) {
                        deletedFiles += 1
                        deletedBytes += size
                    }
                } else {
                    file.delete()
                }
            }
        }

        return LocalImageCleanupResult(
            deletedFiles = deletedFiles,
            deletedBytes = deletedBytes,
        )
    }
}
