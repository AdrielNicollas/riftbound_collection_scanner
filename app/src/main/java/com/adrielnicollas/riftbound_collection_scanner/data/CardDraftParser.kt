package com.adrielnicollas.riftbound_collection_scanner.data

import java.util.Locale

data class CardDraft(
    val name: String,
    val cardNumber: String,
    val cost: Int?,
    val might: Int?,
    val cardType: String,
    val domain: String,
    val effectText: String,
    val rawText: String,
)

object CardDraftParser {
    val cardTypes = listOf(
        "Champion Unit",
        "Champion Legend",
        "Legend",
        "Champion",
        "Unit",
        "Gear",
        "Equipment",
        "Rune",
        "Spell",
        "Battlefield",
        "Event",
    )

    val domains = listOf(
        "Fury",
        "Calm",
        "Mind",
        "Body",
        "Chaos",
        "Order",
        "Noxus",
        "Demacia",
        "Ionia",
        "Freljord",
        "Piltover",
        "Zaun",
        "Bilgewater",
        "Shurima",
        "Targon",
        "Shadow Isles",
        "Bandle City",
    )

    private val costLabelRegex = Regex("""\b(?:cost|custo)\s*[:\-]?\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val standaloneNumberRegex = Regex("""\b\d{1,2}\b""")
    private val labeledCardNumberRegex = Regex(
        """(?i)\b(?:card\s*(?:no\.?|number|#)?|carta|number|numero|num\.?|no\.?|n\.?)\s*(?:#|[:\-])?\s*[A-Z]{0,5}\s*[- ]?\s*\d{1,4}(?:\s*/\s*\d{1,4})?[A-Z]?\b""",
    )
    private val hashCardNumberRegex = Regex("""(?i)#\s*\d{1,4}(?:\s*/\s*\d{1,4})?[A-Z]?\b""")
    private val setCardNumberRegex = Regex("""\b[A-Z]{2,5}\s*-\s*\d{1,4}(?:\s*/\s*\d{1,4})?[A-Z]?\b""")
    private val slashCardNumberRegex = Regex("""(?i)(?<!\d)\d{1,4}\s*/\s*\d{1,4}[A-Z]?(?!\d)""")
    private val footerCardNumberRegex = Regex("""(?i)^\d{3,4}[A-Z]?$""")
    private val onlySymbolsRegex = Regex("""^[\d\W_]+$""")
    private val ignoredNameFragments = listOf("league of legends", "riftbound", "riot games", "riot")

    fun parse(rawText: String): CardDraft {
        val lines = rawText.lineSequence()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .toList()

        val name = detectName(lines)
        val cardNumber = detectCardNumber(rawText, lines)
        val cost = detectCost(rawText, lines)
        val might = detectMight(rawText, lines, cost)
        val cardType = detectOption(lines, cardTypes)
        val domain = detectOption(lines, domains)

        return CardDraft(
            name = name,
            cardNumber = cardNumber,
            cost = cost,
            might = might,
            cardType = cardType,
            domain = domain,
            effectText = detectEffectText(
                lines = lines,
                name = name,
                cardNumber = cardNumber,
                cost = cost,
                might = might,
                cardType = cardType,
                domain = domain,
            ),
            rawText = rawText.trim(),
        )
    }

    private fun detectName(lines: List<String>): String {
        return lines.firstOrNull { line ->
            val lower = line.lowercase(Locale.US)
            line.length > 1 &&
                line.any { it.isLetter() } &&
                !onlySymbolsRegex.matches(line) &&
                !line.matches(standaloneNumberRegex) &&
                !looksLikeCardNumberLine(line) &&
                !costLabelRegex.containsMatchIn(line) &&
                ignoredNameFragments.none { lower.contains(it) } &&
                !looksLikeMetadataLine(line)
        }.orEmpty()
    }

    private fun detectCardNumber(rawText: String, lines: List<String>): String {
        val preferredLines = lines.asReversed()
        val lineMatch = preferredLines.firstNotNullOfOrNull { line ->
            listOfNotNull(
                slashCardNumberRegex.find(line),
                setCardNumberRegex.find(line),
                hashCardNumberRegex.find(line),
                labeledCardNumberRegex.find(line),
            ).firstOrNull()?.value
        }

        val rawMatch = listOfNotNull(
            slashCardNumberRegex.find(rawText),
            setCardNumberRegex.find(rawText),
            hashCardNumberRegex.find(rawText),
            labeledCardNumberRegex.find(rawText),
        ).firstOrNull()?.value

        val footerMatch = preferredLines
            .take(6)
            .firstOrNull { footerCardNumberRegex.matches(it) }

        return cleanCardNumber(lineMatch ?: rawMatch ?: footerMatch.orEmpty())
    }

    private fun detectCost(rawText: String, lines: List<String>): Int? {
        costLabelRegex.find(rawText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..20 }
            ?.let { return it }

        lines.firstOrNull { it.matches(standaloneNumberRegex) && !looksLikeCardNumberLine(it) }
            ?.toIntOrNull()
            ?.takeIf { it in 0..20 }
            ?.let { return it }

        val rawTextWithoutCardNumbers = rawText.replace(slashCardNumberRegex, " ")
            .replace(setCardNumberRegex, " ")
            .replace(hashCardNumberRegex, " ")
            .replace(labeledCardNumberRegex, " ")

        return standaloneNumberRegex.findAll(rawTextWithoutCardNumbers)
            .mapNotNull { it.value.toIntOrNull() }
            .firstOrNull { it in 0..20 }
    }

    private fun detectMight(rawText: String, lines: List<String>, cost: Int?): Int? {
        Regex("""(?i)\b(?:might|power|forca|força)\s*[:\-]?\s*(\d{1,2})\b""")
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in 0..99 }
            ?.let { return it }

        val numericLines = lines
            .filter { it.matches(standaloneNumberRegex) && !looksLikeCardNumberLine(it) }
            .mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 0..99 } }

        return numericLines.firstOrNull { it != cost }
    }

    private fun looksLikeCardNumberLine(line: String): Boolean {
        return slashCardNumberRegex.containsMatchIn(line) ||
            setCardNumberRegex.containsMatchIn(line) ||
            hashCardNumberRegex.containsMatchIn(line) ||
            labeledCardNumberRegex.containsMatchIn(line) ||
            footerCardNumberRegex.matches(line)
    }

    private fun cleanCardNumber(value: String): String {
        return value.trim()
            .trim('.', ',', ';', ':')
            .replace(
                Regex("""(?i)^(?:card\s*(?:no\.?|number|#)?|carta|number|numero|num\.?|no\.?|n\.?|#)\s*(?:#|[:\-])?\s*"""),
                "",
            )
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("""\s*-\s*"""), "-")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun detectEffectText(
        lines: List<String>,
        name: String,
        cardNumber: String,
        cost: Int?,
        might: Int?,
        cardType: String,
        domain: String,
    ): String {
        val ignoredExact = buildSet {
            add(name.cleanComparable())
            add(cardNumber.cleanComparable())
            add(cardType.cleanComparable())
            add(domain.cleanComparable())
            cost?.let { add(it.toString()) }
            might?.let { add(it.toString()) }
        }

        return lines
            .filter { line ->
                val comparable = line.cleanComparable()
                comparable.isNotBlank() &&
                    comparable !in ignoredExact &&
                    line.any { it.isLetter() } &&
                    !onlySymbolsRegex.matches(line) &&
                    !looksLikeCardNumberLine(line) &&
                    !costLabelRegex.containsMatchIn(line) &&
                    ignoredNameFragments.none { comparable.contains(it) } &&
                    !looksLikeMetadataLine(line)
            }
            .joinToString(separator = "\n")
            .trim()
    }

    private fun detectOption(lines: List<String>, options: List<String>): String {
        return options.firstOrNull { option ->
            lines.any { line ->
                line.isMetadataLine() && line.containsOptionTokens(option)
            }
        }.orEmpty()
    }

    private fun looksLikeMetadataLine(line: String): Boolean {
        return line.isMetadataLine()
    }

    private fun String.isMetadataLine(): Boolean {
        val tokens = metadataTokens()
        if (tokens.isEmpty()) return false

        val options = (cardTypes + domains)
            .map { it.metadataTokens() }
            .sortedByDescending { it.size }

        fun consume(remaining: List<String>, usedOptions: Int): Boolean {
            if (remaining.isEmpty()) return usedOptions > 0
            return options.any { option ->
                option.isNotEmpty() &&
                    remaining.size >= option.size &&
                    remaining.take(option.size) == option &&
                    consume(remaining.drop(option.size), usedOptions + 1)
            }
        }

        return consume(tokens, usedOptions = 0)
    }

    private fun String.containsOptionTokens(option: String): Boolean {
        val tokens = metadataTokens()
        val target = option.metadataTokens()
        if (target.isEmpty() || tokens.size < target.size) return false
        return tokens.windowed(target.size).any { it == target }
    }

    private fun String.metadataTokens(): List<String> {
        return cleanComparable()
            .split(" ")
            .filter { it.isNotBlank() }
    }

    private fun String.cleanComparable(): String {
        return lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9/]+"""), " ")
            .trim()
    }
}
