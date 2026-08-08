package com.adrielnicollas.riftbound_collection_scanner.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScanDates {
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    private val dateTimeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }

    fun now(): Long = System.currentTimeMillis()

    fun formatDate(timestamp: Long): String = dateFormat.get()!!.format(Date(timestamp))

    fun formatDateTime(timestamp: Long): String = dateTimeFormat.get()!!.format(Date(timestamp))
}
