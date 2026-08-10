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
        "1 Any",
        "1 Fury",
        "1 Calm",
        "1 Mind",
        "1 Body",
        "1 Chaos",
        "1 Order",
        "1 Order / Body",
        "1 Fury / Body",
        "2 Any",
        "2 Fury",
        "2 Calm",
        "2 Mind",
        "2 Body",
        "2 Chaos",
        "2 Order",
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
        val lines = cleanLines(rawText)

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

    fun parseEffectText(
        rawText: String,
        name: String = "",
        cardNumber: String = "",
        cost: Int? = null,
        might: Int? = null,
        cardType: String = "",
        domain: String = "",
    ): String {
        return detectEffectText(
            lines = cleanLines(rawText),
            name = name,
            cardNumber = cardNumber,
            cost = cost,
            might = might,
            cardType = cardType,
            domain = domain,
        )
    }

    private fun cleanLines(rawText: String): List<String> {
        return rawText.lineSequence()
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun detectName(lines: List<String>): String {
        val headerIndex = lines.indexOfLast { line ->
            looksLikeMetadataLine(line) ||
                looksLikeCardHeaderLine(line) ||
                looksLikeTypeTagLine(line)
        }
        val candidates = lines.withIndex()
            .filter { (_, line) -> looksLikeNameCandidate(line) }

        val selected = (
            candidates.firstOrNull { (index, line) ->
                index > headerIndex &&
                    looksLikeTitleNameLine(line) &&
                    !looksLikeEffectStartLine(line)
            } ?: candidates.firstOrNull { (_, line) ->
                looksLikeTitleNameLine(line) &&
                    !looksLikeEffectStartLine(line)
            } ?: candidates.firstOrNull { (index, line) ->
                index > headerIndex &&
                    !looksLikeEffectStartLine(line) &&
                    !looksLikeNameSubtitleLine(line)
            } ?: candidates.firstOrNull { (_, line) ->
                !looksLikeEffectStartLine(line) &&
                    !looksLikeNameSubtitleLine(line)
            }
        )
            ?: return ""

        val baseName = selected.value.cleanNameLine()
        val subtitle = lines.getOrNull(selected.index + 1)
            ?.takeIf { looksLikeNameSubtitleLine(it) }
            ?.cleanNameLine()
            .orEmpty()

        return if (subtitle.isNotBlank()) "$baseName - $subtitle" else baseName
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
        val match = Regex(
            """(?i)\b(?:power\s*cost|power)\s*[:\-]?\s*(\d{1,2})?\s*(any|fury|calm|mind|body|chaos|order)\b""",
        ).find(rawText) ?: return ""

        val amount = match.groupValues.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "1"
        val domain = match.groupValues.getOrNull(2)
            ?.replaceFirstChar { it.titlecase(Locale.US) }
            .orEmpty()
        return "$amount $domain".trim()
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
            addAll(nameLineComparables(name))
            add(cardNumber.cleanComparable())
            add(cardType.cleanComparable())
            add(domain.cleanComparable())
            cost?.let { add(it.toString()) }
            might?.let { add(it.toString()) }
        }
        val nameLineIndex = lines.indexOfFirst { line ->
            line.cleanNameLine().cleanComparable() in nameLineComparables(name)
        }

        val candidateLines = lines
            .withIndex()
            .filter { (index, line) ->
                val comparable = line.cleanComparable()
                comparable.isNotBlank() &&
                    (nameLineIndex < 0 || index > nameLineIndex) &&
                    comparable !in ignoredExact &&
                    line.any { it.isLetter() } &&
                    !onlySymbolsRegex.matches(line) &&
                    !isLikelySubtitleForName(index, nameLineIndex, line) &&
                    !looksLikeLoreLine(line) &&
                    !looksLikeCardNumberLine(line) &&
                    !looksLikeCostOnlyLine(line) &&
                    ignoredNameFragments.none { comparable.contains(it) } &&
                    !looksLikeMetadataLine(line) &&
                    !looksLikeCardHeaderLine(line) &&
                    !looksLikeFooterCreditLine(line)
            }

        return candidateLines
            .dropLeadingEffectNoise()
            .takeUntilTrailingLore()
            .map { (_, line) -> normalizeEffectSymbols(line) }
            .joinToString(separator = "\n")
            .trim()
    }

    private fun detectCardType(lines: List<String>): String {
        return cardTypes.firstOrNull { option ->
            lines.any { line ->
                (line.isMetadataLine() || looksLikeCardHeaderLine(line) || looksLikeTypeTagLine(line)) &&
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

    private fun looksLikeTypeTagLine(line: String): Boolean {
        val tokens = line.metadataTokens()
        if (tokens.isEmpty()) return false
        val hasType = cardTypes.any { option ->
            val target = option.metadataTokens()
            target.isNotEmpty() &&
                tokens.containsOptionTokens(target)
        }
        if (!hasType) return false
        return looksLikeMetadataStyleText(line) ||
            (
                line.trim() == line.trim().uppercase(Locale.US) &&
                    cardTypes.any { option -> line.uppercase(Locale.US).contains(option.uppercase(Locale.US)) }
                )
    }

    private fun looksLikeNameCandidate(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        return line.length > 1 &&
            line.any { it.isLetter() } &&
            !onlySymbolsRegex.matches(line) &&
            !line.matches(standaloneNumberRegex) &&
            !looksLikeDamagedNumberLine(line) &&
            !looksLikeCardNumberLine(line) &&
            !costLabelRegex.containsMatchIn(line) &&
            ignoredNameFragments.none { lower.contains(it) } &&
            !looksLikeMetadataLine(line) &&
            !looksLikeCardHeaderLine(line) &&
            !looksLikeTypeTagLine(line) &&
            !looksLikeDamagedTypeTagLine(line) &&
            !looksLikeDamagedMetadataLine(line) &&
            !looksLikeFooterCreditLine(line)
    }

    private fun looksLikeDamagedTypeTagLine(line: String): Boolean {
        val comparable = line.cleanComparable()
        if (comparable.isBlank()) return false
        val tokens = comparable.split(" ").filter { it.isNotBlank() }
        if (tokens.size > 5) return false

        val hasChampionLike = tokens.any { token ->
            token.startsWith("champ") || token in setOf("uapion", "champiunt", "champi", "piun")
        }
        val hasUnitLike = tokens.any { token ->
            token == "unit" || token == "uni" || token == "uii" || token == "umt" || token == "unitl"
        }
        val hasKnownTypeLike = tokens.any { token ->
            token in setOf("legend", "unit", "uni", "spell", "gear", "equipment", "battlefield")
        }

        return (hasChampionLike && hasUnitLike) ||
            (line == line.uppercase(Locale.US) && hasKnownTypeLike && tokens.size <= 3)
    }

    private fun looksLikeDamagedMetadataLine(line: String): Boolean {
        val tokens = line.cleanComparable().split(" ").filter { it.isNotBlank() }
        return tokens.any { it in setOf("inmia", "nokus", "nuxus") }
    }

    private fun looksLikeDamagedNumberLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length <= 3 &&
            trimmed.any { it.isDigit() } &&
            trimmed.count { it.isLetter() } <= 1
    }

    private fun looksLikeEffectStartLine(line: String): Boolean {
        val comparable = line.cleanComparable()
        if (comparable.isBlank()) return false
        return effectActionWords.any { comparable.startsWith(it) } ||
            effectKeywordWords.any { comparable == it } ||
            comparable.startsWith("at the start") ||
            comparable.startsWith("at start") ||
            comparable.startsWith("when ") ||
            comparable.startsWith("while ") ||
            comparable.startsWith("if ") ||
            comparable.startsWith("choose ") ||
            comparable.startsWith("draw ") ||
            comparable.startsWith("deal ") ||
            comparable.startsWith("give ") ||
            comparable.startsWith("return ") ||
            comparable.startsWith("ready ") ||
            comparable.startsWith("then ")
    }

    private fun List<IndexedValue<String>>.dropLeadingEffectNoise(): List<IndexedValue<String>> {
        val startIndex = indexOfFirst { (_, line) ->
            looksLikeEffectStartLine(line) || line.cleanComparable().containsAnyEffectWord()
        }
        if (startIndex <= 0) return this
        return drop(startIndex)
    }

    private fun List<IndexedValue<String>>.takeUntilTrailingLore(): List<IndexedValue<String>> {
        if (isEmpty()) return this
        val kept = mutableListOf<IndexedValue<String>>()
        for (entry in this) {
            val hasCompletedEffect = kept.any { (_, previous) -> previous.trim().endsWith(".") }
            if (hasCompletedEffect && looksLikeTrailingLoreLine(entry.value)) {
                break
            }
            kept += entry
        }
        return kept
    }

    private fun looksLikeTrailingLoreLine(line: String): Boolean {
        val trimmed = line.trim()
        val comparable = trimmed.cleanComparable()
        val words = comparable.split(" ").filter { it.isNotBlank() }
        return words.size in 4..16 &&
            !comparable.containsAnyEffectWord() &&
            !looksLikeCardNumberLine(trimmed) &&
            !looksLikeFooterCreditLine(trimmed)
    }

    private fun looksLikeTitleNameLine(line: String): Boolean {
        val trimmed = line.cleanNameLine()
        if (trimmed.length !in 2..48) return false
        if (Regex("""[.,()]""").containsMatchIn(trimmed)) return false
        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return false
        return words.any { word -> word.any { it.isLowerCase() } } ||
            words.any { word -> word.length > 1 && word.first().isUpperCase() }
    }

    private fun looksLikeMetadataStyleText(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (Regex("""[.,()]""").containsMatchIn(trimmed)) return false

        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size > 8) return false
        if (trimmed == trimmed.uppercase(Locale.US)) return true

        return words.all { word ->
            word.firstOrNull()?.isUpperCase() == true ||
                word.all { char -> !char.isLetter() }
        }
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
        val comparable = trimmed.cleanComparable()
        val words = comparable.split(" ").filter { it.isNotBlank() }
        if (
            words.size in 2..8 &&
            (trimmed.endsWith("?") || trimmed.endsWith("!")) &&
            effectActionWords.none { comparable.contains(it) } &&
            effectKeywordWords.none { comparable.contains(it) }
        ) {
            return true
        }
        return trimmed.startsWith("\"") ||
            trimmed.startsWith("“") ||
            trimmed.startsWith("”") ||
            trimmed.startsWith("'") ||
            trimmed.startsWith("‘") ||
            trimmed.startsWith("’")
    }

    private fun looksLikeCostOnlyLine(line: String): Boolean {
        return Regex("""(?i)^\s*(?:cost|custo)\s*[:\-]?\s*\d{1,2}\s*$""").matches(line)
    }

    private fun nameLineComparables(name: String): Set<String> {
        return name
            .split(Regex("""\s+[-–—]\s+|,\s+"""))
            .map { it.cleanComparable() }
            .filter { it.isNotBlank() }
            .toSet()
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
            .replace(Regex("""(?:[\u00FD\u00FF]|\u00C3[\u00BD\u00BF]|\u00E1\u00BB[\u00A6\u00B7])"""), "[Might]")
            .replace(Regex("""(?<=\d)\s*[uU]\s*(?=\w)"""), " [Might] ")
            .replace(Regex("""(?<=\d)\s*[uU]\b"""), " [Might]")
            .replace(Regex("""(?<=\+)\s*[uU]\s*(?=\w)"""), " [Might] ")
            .replace(Regex("""(?<=-)\s*[uU]\s*(?=\w)"""), "[Might] ")
            .replace(Regex("""\b[uU](?=to\b)"""), "[Might] ")
            .replace(Regex("""\b[uU](?=\s+(?:this|turn|to|while|until)\b)"""), "[Might]")
            .replace(Regex("""(?<=\d)\s*[yY]\b"""), "[Might]")
            .replace(Regex("""([+-])\s*/\s*(?=\[Might])""")) { match ->
                "${match.groupValues[1]}1 "
            }
            .replace(Regex("""([+-])\s*/\s*[uU]\b""")) { match ->
                "${match.groupValues[1]}1 [Might]"
            }
            .replace(Regex("""(?i)([+-])\s*[lI]\s*(?=\[Might])""")) { match ->
                "${match.groupValues[1]}1 "
            }

        return annotateRuneCosts(normalizedMight)
            .replace(Regex("""(?i)\bdrawl\b"""), "draw 1")
            .replace(Regex("""(?i)\bdraw(?=\d)""")) { match ->
                if (match.value.firstOrNull()?.isUpperCase() == true) "Draw " else "draw "
            }
            .replace(Regex("""(?i)\bdraw\s+[lI]\b"""), "draw 1")
            .replace(Regex("""(?i)\bscore\s+[lI]\b"""), "score 1")
            .replace(Regex("""(?i)\bgnoring\b"""), "ignoring")
            .replace(Regex("""(?i)\bUse only if at Empowered\b"""), "Use only if not Empowered")
            .replace(Regex("""(?i)\bUse only if at$"""), "Use only if not")
            .replace(Regex("""(?i)\bIfa\b"""), "If a")
            .replace(Regex("""(?<=\[Might])\s*/\s+(?=or\s+less\b)""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun annotateRuneCosts(line: String): String {
        return line
            .replace(Regex("""\[(?:Rune|Runo|Runa|Runas?)\]""", RegexOption.IGNORE_CASE), "[Rune]")
            .replace(Regex("""(?i)\bE[OQ]UIP\s+[<(\[]?[0Oo][>)\]]?\s*\(\s*[<(\[]?[0Oo][>)\]]?\s*:""")) {
                "EQUIP 1[Power] > (1[Power]:"
            }
            .replace(Regex("""(?i)\b(EQUIP)\s+0\s*>\s*\(\s*0\s*:""")) { match ->
                "${match.groupValues[1]} 1[Power] > (1[Power]:"
            }
            .replace(Regex("""(?i)\b(EMPOWER)\s+(\d{1,2})(?!\s*\[Rune])""")) { match ->
                "${match.groupValues[1]} ${match.groupValues[2]}[Rune]"
            }
            .replace(Regex("""(?<=\()\s*([1-9]\d?)(?!\s*\[Rune])(?=\s+(?:\(?[A-Z]\)?|\[Might]|1\s*\[Power|[A-Za-z]+:))""")) { match ->
                "${match.groupValues[1]}[Rune]"
            }
            .replace(Regex("""(?<=\()\s*([1-9]\d?)(?!\s*\[Rune])(?=\s*:)""")) { match ->
                "${match.groupValues[1]}[Rune]"
            }
            .replace(Regex("""(?i)([1-9]\d?)(?!\s*\[Rune])\s*>\s*\(""")) { match ->
                "${match.groupValues[1]}[Rune] > ("
            }
            .replace(Regex("""(?i)\b(costs?\s+)(\d{1,2})(?!\s*\[Rune])(?=\s+(?:or\s+less|less))""")) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[Rune]"
            }
            .replace(Regex("""(?i)\b(costs?\s+no\s+more\s+than\s+)(\d{1,2})(?!\s*\[Rune])""")) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}[Rune]"
            }
    }

    private fun String.containsOptionTokens(option: String): Boolean {
        val tokens = metadataTokens()
        val target = option.metadataTokens()
        return tokens.containsOptionTokens(target)
    }

    private fun String.containsAnyEffectWord(): Boolean {
        return effectActionWords.any { word ->
            this == word || startsWith("$word ") || contains(" $word ")
        } || effectKeywordWords.any { word ->
            this == word || startsWith("$word ") || contains(" $word ")
        }
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
        "attach",
        "choose",
        "cost",
        "countered",
        "create",
        "defend",
        "discard",
        "draw",
        "empower",
        "give",
        "gives",
        "have",
        "increase",
        "play",
        "ready",
        "return",
        "summon",
        "unit",
    )

    private val effectKeywordWords = listOf(
        "ambush",
        "action",
        "deflect",
        "empowered",
        "exhaust",
        "ganking",
        "hidden",
        "legion",
        "mighty",
        "reaction",
        "shield",
        "tank",
    )
}
