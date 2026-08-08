package com.adrielnicollas.riftbound_collection_scanner.data

import java.text.Normalizer
import java.util.Locale

object CardKeys {
    fun build(name: String, cardNumber: String): String {
        val normalizedName = normalizeName(name)
        val normalizedNumber = normalizeNumber(cardNumber)
        return if (normalizedNumber.isBlank()) normalizedName else "$normalizedName|$normalizedNumber"
    }

    fun normalizeName(value: String): String {
        return stripAccents(value)
            .lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    fun normalizeNumber(value: String): String {
        return stripAccents(value)
            .uppercase(Locale.US)
            .replace(Regex("""\s+"""), "")
            .trim()
    }

    private fun stripAccents(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
    }
}
