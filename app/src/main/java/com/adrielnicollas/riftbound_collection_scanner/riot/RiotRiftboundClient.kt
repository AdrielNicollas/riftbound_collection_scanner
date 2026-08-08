package com.adrielnicollas.riftbound_collection_scanner.riot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.adrielnicollas.riftbound_collection_scanner.BuildConfig
import com.adrielnicollas.riftbound_collection_scanner.data.CardDraft
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class RiotRiftboundClient(
    private val apiKey: String = BuildConfig.RIOT_API_KEY,
) {
    private var cachedCards: List<RiotRiftboundCard>? = null

    fun findBestMatch(draft: CardDraft): RiotRiftboundCard? {
        if (apiKey.isBlank()) return null
        val cards = loadCards().orEmpty()
        if (cards.isEmpty()) return null

        val requestedNumber = draft.cardNumber.normalizedCardNumber()
        if (requestedNumber.isNotBlank()) {
            cards.firstOrNull { card ->
                val candidateNumber = card.cardNumber.normalizedCardNumber()
                candidateNumber == requestedNumber || candidateNumber.endsWith(requestedNumber)
            }?.let { return it }
        }

        val requestedName = draft.name.normalizedName()
        if (requestedName.isBlank()) return null
        return cards.firstOrNull { it.name.normalizedName() == requestedName }
            ?: cards.firstOrNull { it.name.normalizedName().contains(requestedName) }
    }

    fun downloadCardImage(card: RiotRiftboundCard, destinationFile: File): Boolean {
        val imageUrl = card.imageUrl?.takeIf { it.isNotBlank() } ?: return false
        return try {
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.inputStream.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return false
                destinationFile.parentFile?.mkdirs()
                FileOutputStream(destinationFile).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                bitmap.recycle()
            }
            true
        } catch (exception: Exception) {
            false
        }
    }

    private fun loadCards(): List<RiotRiftboundCard>? {
        cachedCards?.let { return it }

        val cards = CONTENT_ROUTES.firstNotNullOfOrNull { route ->
            fetchContents(route)?.let(::parseCards)
        }
        cachedCards = cards
        return cards
    }

    private fun fetchContents(route: String): String? {
        return try {
            val url = URL("https://$route.api.riotgames.com/riftbound/content/v1/contents")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("X-Riot-Token", apiKey)
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (exception: Exception) {
            null
        }
    }

    private fun parseCards(json: String): List<RiotRiftboundCard> {
        val root = JSONObject(json)
        val cardsArray = root.optJSONArray("cards")
            ?: root.optJSONArray("data")
            ?: root.firstArray()
            ?: return emptyList()

        return buildList {
            for (index in 0 until cardsArray.length()) {
                val card = cardsArray.optJSONObject(index)?.toRiotCard() ?: continue
                if (card.name.isNotBlank()) add(card)
            }
        }
    }

    private fun JSONObject.toRiotCard(): RiotRiftboundCard {
        return RiotRiftboundCard(
            name = firstString("name", "cardName", "title"),
            cardNumber = firstString("cardNumber", "card_number", "printed_number", "printedNumber", "collectorNumber", "number"),
            type = firstString("type", "cardType", "card_type"),
            domain = firstString("domain", "domains", "energy", "region"),
            effectText = firstString("rules", "rulesText", "ruleText", "effect", "effectText", "text", "description"),
            imageUrl = imageUrl(),
        )
    }

    private fun JSONObject.firstString(vararg keys: String): String {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            when (val value = opt(key)) {
                is String -> return value.trim()
                is JSONArray -> value.firstText()?.let { return it }
                is JSONObject -> value.firstText("name", "text", "value", "label")?.let { return it }
            }
        }
        return ""
    }

    private fun JSONObject.imageUrl(): String? {
        optJSONObject("art")?.firstText("fullURL", "fullUrl", "thumbnailURL", "thumbnailUrl", "url")?.let { return it }
        optJSONObject("images")?.firstText("large", "medium", "small", "full", "url")?.let { return it }
        optJSONArray("media")?.let { media ->
            val fallback = mutableListOf<String>()
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index) ?: continue
                val url = item.firstString("url", "uri")
                if (url.isBlank()) continue
                fallback += url
                val label = "${item.firstString("type")} ${item.firstString("name")}".lowercase(Locale.US)
                if (label.contains("card") || label.contains("image") || label.contains("full")) return url
            }
            return fallback.firstOrNull()
        }
        return firstString("image", "imageUrl", "imageURL").takeIf { it.isNotBlank() }
    }

    private fun JSONObject.firstArray(): JSONArray? {
        keys().forEach { key ->
            optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun JSONArray.firstText(): String? {
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is String -> return value.trim().takeIf { it.isNotBlank() }
                is JSONObject -> value.firstText("name", "text", "value", "label")?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstText(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun String.normalizedCardNumber(): String {
        return uppercase(Locale.US)
            .replace(Regex("""\s+"""), "")
            .trim('#')
    }

    private fun String.normalizedName(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        return normalized.lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private companion object {
        private const val TIMEOUT_MS = 7000
        private val CONTENT_ROUTES = listOf("americas", "europe", "asia")
    }
}

data class RiotRiftboundCard(
    val name: String,
    val cardNumber: String,
    val type: String,
    val domain: String,
    val effectText: String,
    val imageUrl: String?,
)
