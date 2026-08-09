package com.adrielnicollas.riftbound_collection_scanner.data

import java.util.Locale

data class CardDraft(
    val name: String,
    val cardNumber: String,
    val cardSet: String,
    val cost: Int?,
    val powerCost: String,
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
    )

    val powerCosts = listOf(
        "Any",
        "Fury",
        "Calm",
        "Mind",
        "Body",
        "Chaos",
        "Order",
    )

    val cardSets = listOf(
        "OGN",
        "OGS",
        "SFD",
        "UNL",
        "VEN",
    )

    private val regions = listOf(
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
        """(?i)\b(?:card\s*(?:no\.?|number|#)?|carta|number|numero|num\.?|no\.?|n\.?)\s*(?:#|[:\-])?\s*[A-Z]{0,5}\s*[- ]?\s*\d{1,4}[A-Z]?(?:\s*/\s*\d{1,4}[A-Z]?)?\b""",
    )
    private val hashCardNumberRegex = Regex("""(?i)#\s*\d{1,4}[A-Z]?(?:\s*/\s*\d{1,4}[A-Z]?)?\b""")
    private val setCardNumberRegex = Regex("""(?i)\b[A-Z]{2,5}\s*-\s*\d{1,4}[A-Z]?(?:\s*/\s*\d{1,4}[A-Z]?)?\b""")
    private val slashCardNumberRegex = Regex("""(?i)(?<![A-Z0-9])[0-9OIL]{1,4}[A-Z]?\s*/\s*[0-9OIL]{1,4}[A-Z]?(?![A-Z0-9])""")
    private val cardSetRegex = Regex("""(?i)(?<![A-Z0-9])(?:OGN|OGS|SFD|UNL|VEN)(?![A-Z0-9])""")
    private val footerCardNumberRegex = Regex("""(?i)^\d{3,4}[A-Z]?$""")
    private val onlySymbolsRegex = Regex("""^[\d\W_]+$""")
    private val ignoredNameFragments = listOf("league of legends", "riftbound", "riot games", "riot")
    private val footerCopyrightRegex = Regex("""(?i)(?:\\u00a9|copyright|20\d{2}\s*(?:rgi|rg|riot))""")
    private val knownArtistCreditFragments = listOf(
        "kudos productions",
        "league splash team",
        "michal ivan",
        "paindart studio",
        "six more vodka",
    )
    private val ocrTokenCorrections = mapOf(
        "uapion" to "champion",
        "uampion" to "champion",
        "champ1on" to "champion",
        "champlon" to "champion",
        "nokus" to "noxus",
        "n0xus" to "noxus",
    )

    fun parse(rawText: String): CardDraft {
        val lines = rawText.lineSequence()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .toList()

        val name = detectName(lines)
        val cardNumber = detectCardNumber(rawText, lines)
        val cardSet = detectCardSet(rawText, lines)
        val cost = detectCost(rawText, lines)
        val powerCost = detectPowerCost(rawText)
        val might = detectMight(rawText, lines, cost)
        val cardType = detectCardType(lines)
        val domain = detectDomain(lines)

        return CardDraft(
            name = name,
            cardNumber = cardNumber,
            cardSet = cardSet,
            cost = cost,
            powerCost = powerCost,
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
                !looksLikeMetadataLine(line) &&
                !looksLikeCardHeaderLine(line) &&
                !looksLikeFooterCreditLine(line)
        }
            ?.cleanNameLine()
            .orEmpty()
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

    private fun detectCardSet(rawText: String, lines: List<String>): String {
        val preferredLines = lines.asReversed()
        val lineMatch = preferredLines.firstNotNullOfOrNull { line ->
            cardSetRegex.find(line)?.value
        }
        val rawMatch = cardSetRegex.find(rawText)?.value
        return (lineMatch ?: rawMatch).orEmpty().uppercase(Locale.US)
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

        if (numericLines.size >= 2) {
            return numericLines.drop(1).firstOrNull()
        }

        return numericLines.firstOrNull { it != cost }
    }

    private fun detectPowerCost(rawText: String): String {
        return Regex("""(?i)\b(?:power\s*cost|power)\s*[:\-]?\s*(any|fury|calm|mind|body|chaos|order)\b""")
            .find(rawText)
            ?.groupValues
            ?.getOrNull(1)
            ?.replaceFirstChar { it.titlecase(Locale.US) }
            .orEmpty()
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
            .replaceOcrDigitsInCardNumber()
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
        val nameLineIndex = lines.indexOfFirst { line ->
            line.cleanNameLine().cleanComparable() == name.cleanComparable()
        }

        return lines
            .withIndex()
            .filter { (index, line) ->
                val comparable = line.cleanComparable()
                comparable.isNotBlank() &&
                    comparable !in ignoredExact &&
                    line.any { it.isLetter() } &&
                    !onlySymbolsRegex.matches(line) &&
                    !isLikelySubtitleForName(index, nameLineIndex, line) &&
                    !looksLikeLoreLine(line) &&
                    !looksLikeCardNumberLine(line) &&
                    !costLabelRegex.containsMatchIn(line) &&
                    ignoredNameFragments.none { comparable.contains(it) } &&
                    !looksLikeMetadataLine(line) &&
                    !looksLikeCardHeaderLine(line) &&
                    !looksLikeFooterCreditLine(line)
            }
            .map { (_, line) -> normalizeEffectSymbols(line) }
            .joinToString(separator = "\n")
            .trim()
    }

    private fun detectCardType(lines: List<String>): String {
        return cardTypes.firstOrNull { option ->
            lines.any { line ->
                (line.isMetadataLine() || looksLikeCardHeaderLine(line)) &&
                    line.containsOptionTokens(option)
            }
        }.orEmpty()
    }

    private fun detectDomain(lines: List<String>): String {
        val detected = mutableListOf<String>()
        lines
            .filter { it.isMetadataLine() }
            .forEach { line ->
                line.metadataTokens().forEach { token ->
                    val domain = domains.firstOrNull { it.cleanComparable() == token }
                    if (domain != null && domain !in detected) {
                        detected += domain
                    }
                }
            }

        return detected.joinToString(separator = " / ")
    }

    private fun looksLikeMetadataLine(line: String): Boolean {
        return line.isMetadataLine()
    }

    private fun String.isMetadataLine(): Boolean {
        val tokens = metadataTokens()
        if (tokens.isEmpty()) return false

        val options = (cardTypes + domains + regions)
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

    private fun looksLikeCardHeaderLine(line: String): Boolean {
        val tokens = line.metadataTokens()
        val hasType = cardTypes.any { option ->
            tokens.containsOptionTokens(option.metadataTokens())
        }
        val hasRegion = regions.any { region ->
            tokens.containsOptionTokens(region.metadataTokens())
        }
        return hasType && hasRegion
    }

    private fun isLikelySubtitleForName(index: Int, nameLineIndex: Int, line: String): Boolean {
        if (nameLineIndex < 0 || index != nameLineIndex + 1) return false
        return looksLikeNameSubtitleLine(line)
    }

    private fun looksLikeNameSubtitleLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.length !in 2..40 || !trimmed.any { it.isLetter() }) return false
        if (trimmed != trimmed.uppercase(Locale.US)) return false
        if (looksLikeCardNumberLine(trimmed) || looksLikeCardHeaderLine(trimmed) || looksLikeMetadataLine(trimmed)) {
            return false
        }

        val comparable = trimmed.cleanComparable()
        if (comparable.isBlank()) return false
        if (effectActionWords.any { comparable.contains(it) }) return false
        if (effectKeywordWords.any { comparable.contains(it) }) return false
        return comparable.split(" ").filter { it.isNotBlank() }.size <= 4
    }

    private fun looksLikeLoreLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("\"") ||
            trimmed.startsWith("“") ||
            trimmed.startsWith("”") ||
            trimmed.startsWith("'") ||
            trimmed.startsWith("‘") ||
            trimmed.startsWith("’")
    }

    private fun looksLikeFooterCreditLine(line: String): Boolean {
        val comparable = line.cleanComparable()
        if (line.any { it.code == COPYRIGHT_CODE_POINT } || footerCopyrightRegex.containsMatchIn(line)) {
            return true
        }
        if (knownArtistCreditFragments.any { comparable.contains(it) }) {
            return true
        }

        val trimmed = line.trim()
        if (!trimmed.startsWith("/") && !trimmed.startsWith("\\")) {
            return false
        }
        if (looksLikeCardNumberLine(line)) {
            return false
        }

        val tokens = comparable.split(" ").filter { it.isNotBlank() }
        return tokens.size in 2..6 &&
            tokens.all { token -> token.all { char -> char.isLetter() || char.isDigit() } } &&
            effectActionWords.none { comparable.contains(it) }
    }

    private fun normalizeEffectSymbols(line: String): String {
        val normalizedMight = line
            .replace(Regex("""[ýÿ]""", RegexOption.IGNORE_CASE), "[Might]")
            .replace(Regex("""(?<=\d)\s*[uU]\s*(?=\w)"""), " [Might] ")
            .replace(Regex("""(?<=\d)\s*[uU]\b"""), " [Might]")
            .replace(Regex("""(?<=\+)\s*[uU]\s*(?=\w)"""), " [Might] ")
            .replace(Regex("""(?<=-)\s*[uU]\s*(?=\w)"""), "[Might] ")
            .replace(Regex("""\b[uU](?=to\b)"""), "[Might] ")
            .replace(Regex("""\b[uU](?=\s+(?:this|turn|to|while|until)\b)"""), "[Might]")

        return annotateRuneCosts(normalizedMight)
            .replace(Regex("""(?i)\bUse only if at Empowered\b"""), "Use only if not Empowered")
            .replace(Regex("""(?i)\bUse only if at$"""), "Use only if not")
            .replace(Regex("""(?i)\bIfa\b"""), "If a")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun annotateRuneCosts(line: String): String {
        return line
            .replace(Regex("""(?i)\b(EMPOWER)\s+(\d{1,2})(?!\s*\[Rune])""")) { match ->
                "${match.groupValues[1]} ${match.groupValues[2]}[Rune]"
            }
            .replace(Regex("""(?<=\()\s*(\d{1,2})(?!\s*\[Rune])(?=\s+(?:\(?[A-Z]\)?|\[Might]|1\s*\[Power|[A-Za-z]+:))""")) { match ->
                "${match.groupValues[1]}[Rune]"
            }
            .replace(Regex("""(?<=\()\s*(\d{1,2})(?!\s*\[Rune])(?=\s*:)""")) { match ->
                "${match.groupValues[1]}[Rune]"
            }
            .replace(Regex("""(?i)(\d{1,2})(?!\s*\[Rune])\s*>\s*\(""")) { match ->
                "${match.groupValues[1]}[Rune] > ("
            }
    }

    private fun String.containsOptionTokens(option: String): Boolean {
        val tokens = metadataTokens()
        val target = option.metadataTokens()
        return tokens.containsOptionTokens(target)
    }

    private fun List<String>.containsOptionTokens(target: List<String>): Boolean {
        if (target.isEmpty() || size < target.size) return false
        return windowed(target.size).any { it == target }
    }

    private fun String.metadataTokens(): List<String> {
        return cleanComparable()
            .split(" ")
            .filter { it.isNotBlank() }
            .map { token -> ocrTokenCorrections[token] ?: token }
    }

    private fun String.cleanComparable(): String {
        return lowercase(Locale.US)
            .replace(Regex("""[^a-z0-9/]+"""), " ")
            .trim()
    }

    private fun String.cleanNameLine(): String {
        return trim()
            .trim('-', '–', '—', '•', ':')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.replaceOcrDigitsInCardNumber(): String {
        val slashIndex = indexOf('/')
        if (slashIndex < 0) return this
        val prefix = substring(0, slashIndex)
        val suffix = substring(slashIndex + 1)
        return prefix.replaceOcrDigitCharacters() + "/" + suffix.replaceOcrDigitCharacters()
    }

    private fun String.replaceOcrDigitCharacters(): String {
        return replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
    }

    private const val COPYRIGHT_CODE_POINT = 0x00A9

    private val effectActionWords = listOf(
        "attack",
        "defend",
        "draw",
        "empower",
        "give",
        "play",
        "ready",
        "return",
        "summon",
        "unit",
    )

    private val effectKeywordWords = listOf(
        "ambush",
        "deflect",
        "empowered",
        "exhaust",
        "ganking",
        "mighty",
        "shield",
    )
}
