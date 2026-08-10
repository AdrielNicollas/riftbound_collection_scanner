package com.adrielnicollas.riftbound_collection_scanner

import com.adrielnicollas.riftbound_collection_scanner.data.CardDraftParser
import org.junit.Assert.assertEquals
import org.junit.Test

class CardDraftParserTest {
    @Test
    fun parsesBasicCardFields() {
        val draft = CardDraftParser.parse(
            """
            3
            Mighty Poro
            Unit
            Body
            """.trimIndent(),
        )

        assertEquals("Mighty Poro", draft.name)
        assertEquals("", draft.cardNumber)
        assertEquals(3, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Unit", draft.cardType)
        assertEquals("Body", draft.domain)
        assertEquals("", draft.effectText)
    }

    @Test
    fun skipsBrandingWhenChoosingName() {
        val draft = CardDraftParser.parse(
            """
            Riftbound
            Cost 2
            Lux
            Champion Unit
            Order
            """.trimIndent(),
        )

        assertEquals("Lux", draft.name)
        assertEquals("", draft.cardNumber)
        assertEquals(2, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("Order", draft.domain)
        assertEquals("", draft.effectText)
    }

    @Test
    fun parsesSlashCardNumberWithoutConfusingItWithCost() {
        val draft = CardDraftParser.parse(
            """
            Riftbound
            015/298
            Mystic Shot
            Spell
            Mind
            """.trimIndent(),
        )

        assertEquals("Mystic Shot", draft.name)
        assertEquals("015/298", draft.cardNumber)
        assertEquals(null, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Spell", draft.cardType)
        assertEquals("Mind", draft.domain)
        assertEquals("", draft.effectText)
    }

    @Test
    fun parsesSetCodeCardNumber() {
        val draft = CardDraftParser.parse(
            """
            4
            Garen
            Champion Unit
            OGN-123
            Order
            """.trimIndent(),
        )

        assertEquals("Garen", draft.name)
        assertEquals("OGN-123", draft.cardNumber)
        assertEquals("OGN", draft.cardSet)
        assertEquals(4, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("Order", draft.domain)
        assertEquals("", draft.effectText)
    }

    @Test
    fun parsesLabeledCardNumber() {
        val draft = CardDraftParser.parse(
            """
            1
            Flame Chompers
            Gear
            Card No. 123/298
            Fury
            """.trimIndent(),
        )

        assertEquals("Flame Chompers", draft.name)
        assertEquals("123/298", draft.cardNumber)
        assertEquals(1, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Gear", draft.cardType)
        assertEquals("Fury", draft.domain)
        assertEquals("", draft.effectText)
    }

    @Test
    fun parsesHashCardNumber() {
        val draft = CardDraftParser.parse(
            """
            2
            Recall
            Spell
            #087
            Calm
            """.trimIndent(),
        )

        assertEquals("Recall", draft.name)
        assertEquals("087", draft.cardNumber)
        assertEquals(2, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Spell", draft.cardType)
        assertEquals("Calm", draft.domain)
        assertEquals("", draft.effectText)
    }

    @Test
    fun extractsEffectTextWithoutCardMetadata() {
        val draft = CardDraftParser.parse(
            """
            2
            Mystic Shot
            Spell
            Mind
            Deal 2 damage to a unit.
            015/298
            """.trimIndent(),
        )

        assertEquals("Mystic Shot", draft.name)
        assertEquals("015/298", draft.cardNumber)
        assertEquals(2, draft.cost)
        assertEquals(null, draft.might)
        assertEquals("Spell", draft.cardType)
        assertEquals("Mind", draft.domain)
        assertEquals("Deal 2 damage to a unit.", draft.effectText)
    }

    @Test
    fun keepsMultilineEffectText() {
        val draft = CardDraftParser.parse(
            """
            Lux
            Champion Unit
            Order
            When you cast a spell, ready this unit.
            Once each turn, create a Spark.
            087
            """.trimIndent(),
        )

        assertEquals("Lux", draft.name)
        assertEquals("087", draft.cardNumber)
        assertEquals(null, draft.might)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("Order", draft.domain)
        assertEquals(
            "When you cast a spell, ready this unit.\nOnce each turn, create a Spark.",
            draft.effectText,
        )
    }

    @Test
    fun parsesCombinedTypeAndRegionLineWithoutUsingItAsNameOrEffect() {
        val draft = CardDraftParser.parse(
            """
            UNIT NOXUS
            Arena Kingpin
            When this unit attacks, deal 1 damage.
            042/298
            """.trimIndent(),
        )

        assertEquals("Arena Kingpin", draft.name)
        assertEquals("042/298", draft.cardNumber)
        assertEquals(null, draft.might)
        assertEquals("Unit", draft.cardType)
        assertEquals("", draft.domain)
        assertEquals("When this unit attacks, deal 1 damage.", draft.effectText)
    }

    @Test
    fun parsesCombinedCardTypeAndClassicDomainLine() {
        val draft = CardDraftParser.parse(
            """
            Spell Mind
            Mystic Shot
            Deal 2 damage to a unit.
            015/298
            """.trimIndent(),
        )

        assertEquals("Mystic Shot", draft.name)
        assertEquals("015/298", draft.cardNumber)
        assertEquals(null, draft.might)
        assertEquals("Spell", draft.cardType)
        assertEquals("Mind", draft.domain)
        assertEquals("Deal 2 damage to a unit.", draft.effectText)
    }

    @Test
    fun parsesCostAndMightFromSeparateNumbers() {
        val draft = CardDraftParser.parse(
            """
            3
            2
            UNIT NOXUS
            Reckless Trifarian
            When this unit attacks, give it +1 might.
            055/298
            """.trimIndent(),
        )

        assertEquals("Reckless Trifarian", draft.name)
        assertEquals("055/298", draft.cardNumber)
        assertEquals(3, draft.cost)
        assertEquals(2, draft.might)
        assertEquals("Unit", draft.cardType)
        assertEquals("", draft.domain)
        assertEquals("When this unit attacks, give it +1 might.", draft.effectText)
    }

    @Test
    fun parsesCostAndMightWhenTheyHaveTheSameValue() {
        val draft = CardDraftParser.parse(
            """
            4
            4
            CHAMPION UNIT FIORA DEMACIA
            Fiora
            While I'm MIGHTY, I have DEFLECT.
            "I long for a worthy opponent."
            232/298
            """.trimIndent(),
        )

        assertEquals("Fiora", draft.name)
        assertEquals("232/298", draft.cardNumber)
        assertEquals(4, draft.cost)
        assertEquals(4, draft.might)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("", draft.domain)
        assertEquals("While I'm MIGHTY, I have DEFLECT.", draft.effectText)
    }

    @Test
    fun removesLoreAndNormalizesMightSymbolsInEffect() {
        val draft = CardDraftParser.parse(
            """
            4
            4
            CHAMPION UNIT FIORA DEMACIA
            Fiora
            While I'm MIGHTY, I have DEFLECT, GANKING,
            and SHIELD. (I'm Mighty while I have 5+ ý.)
            "I long for a worthy opponent."
            OGN 232/298
            """.trimIndent(),
        )

        assertEquals("Fiora", draft.name)
        assertEquals("OGN", draft.cardSet)
        assertEquals(4, draft.cost)
        assertEquals(4, draft.might)
        assertEquals(
            "While I'm MIGHTY, I have DEFLECT, GANKING,\nand SHIELD. (I'm Mighty while I have 5+ [Might].)",
            draft.effectText,
        )
    }

    @Test
    fun removesArtistCreditsAndCopyrightFromEffectText() {
        val draft = CardDraftParser.parse(
            """
            5
            5
            UNIT NOXUS
            Dame the Despoiler
            EMPOWER 5 > (5 (O): Empower me. Use only if
            not Empowered.)
            EMPOWERED When I attack or defend, choose a
            unit here. Increase my Might to its Might this turn,
            then give me +1ý this turn.
            "Wanna see a show?!"
            / Kudos Productions \u00A9 2026RGI
            VEN 079/166 EN
            """.trimIndent(),
        )

        assertEquals("Dame the Despoiler", draft.name)
        assertEquals("079/166", draft.cardNumber)
        assertEquals("VEN", draft.cardSet)
        assertEquals(
            "EMPOWER 5[Rune] > (5[Rune] (O): Empower me. Use only if\n" +
                "not Empowered.)\n" +
                "EMPOWERED When I attack or defend, choose a\n" +
                "unit here. Increase my Might to its Might this turn,\n" +
                "then give me +1[Might] this turn.",
            draft.effectText,
        )
    }

    @Test
    fun removesKnownArtistLineBesideDomainSymbol() {
        val draft = CardDraftParser.parse(
            """
            4
            4
            CHAMPION UNIT FIORA DEMACIA
            Fiora
            While I'm MIGHTY, I have DEFLECT.
            / Six More Vodka
            \u00A92025RGI
            OGN 232/298
            """.trimIndent(),
        )

        assertEquals("Fiora", draft.name)
        assertEquals("OGN", draft.cardSet)
        assertEquals("While I'm MIGHTY, I have DEFLECT.", draft.effectText)
    }

    @Test
    fun parsesSetCodeBesideCardNumber() {
        val draft = CardDraftParser.parse(
            """
            4
            3
            CHAMPION UNIT DIANA MOUNT TARGON
            Diana
            When you play a spell, give me +2 this turn.
            UNL 149a/219
            """.trimIndent(),
        )

        assertEquals("Diana", draft.name)
        assertEquals("UNL", draft.cardSet)
        assertEquals("149a/219", draft.cardNumber)
    }

    @Test
    fun ignoresRegionHeaderForDianaAndKeepsChaosDomainWhenOcrReadsIt() {
        val draft = CardDraftParser.parse(
            """
            4
            3
            Chaos
            CHAMPION UNIT DIANA MOUNT TARGON
            Diana
            AMBUSH
            When you play a spell, give me +2 this turn.
            149a/219
            """.trimIndent(),
        )

        assertEquals("Diana", draft.name)
        assertEquals("149a/219", draft.cardNumber)
        assertEquals(4, draft.cost)
        assertEquals(3, draft.might)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("Chaos", draft.domain)
        assertEquals("AMBUSH\nWhen you play a spell, give me +2 this turn.", draft.effectText)
    }

    @Test
    fun preservesMultiplePowerDomainsInOcrOrder() {
        val draft = CardDraftParser.parse(
            """
            2
            Order Body
            Spell
            Arise
            Return a unit from your trash.
            010/219
            """.trimIndent(),
        )

        assertEquals("Arise", draft.name)
        assertEquals("Order / Body", draft.domain)
        assertEquals("Spell", draft.cardType)
    }

    @Test
    fun skipsOcrDamagedHeaderAndKeepsNameOutOfEffect() {
        val draft = CardDraftParser.parse(
            """
            3
            UAPION UNIT NOKUS
            - Mel NEWLY AWAKENED -
            When you play a spell, give me +1 [Might].
            VEN O69/166 EN
            """.trimIndent(),
        )

        assertEquals("Mel NEWLY AWAKENED", draft.name)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("069/166", draft.cardNumber)
        assertEquals("VEN", draft.cardSet)
        assertEquals("When you play a spell, give me +1 [Might].", draft.effectText)
    }

    @Test
    fun annotatesRuneAndMightSymbolsInEffectText() {
        val draft = CardDraftParser.parse(
            """
            3
            UNIT NOXUS
            Test Card
            EMPOWER 3 > (3 Uto Empower me.)
            When I attack, give me +1U this turn.
            069/166
            """.trimIndent(),
        )

        assertEquals(
            "EMPOWER 3[Rune] > (3[Rune] [Might] to Empower me.)\n" +
                "When I attack, give me +1 [Might] this turn.",
            draft.effectText,
        )
    }

    @Test
    fun parsesMelScanWithoutUsingHeaderAsNameOrSubtitleAsEffect() {
        val draft = CardDraftParser.parse(
            """
            4
            UAPION UNIT MEI• NOKUS
            Mel
            NEWLY AWAKENED
            When you play me, draw 1.
            EMPOWER 3: Empower me. Use only if at
            Empowered.)
            EMPOWERED Your spells and abilities can't be
            countered. Ifa spell or ability you control would give
            -Uto a unit it chooses, it gives an additional -1U.
            / Paindart Studio • ©2026RGI
            VEN 069/166 EN
            """.trimIndent(),
        )

        assertEquals("Mel - NEWLY AWAKENED", draft.name)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("VEN", draft.cardSet)
        assertEquals("069/166", draft.cardNumber)
        assertEquals(
            "When you play me, draw 1.\n" +
                "EMPOWER 3[Rune]: Empower me. Use only if not\n" +
                "Empowered.)\n" +
                "EMPOWERED Your spells and abilities can't be\n" +
                "countered. If a spell or ability you control would give\n" +
                "-[Might] to a unit it chooses, it gives an additional -1 [Might].",
            draft.effectText,
        )
    }

    @Test
    fun parsesDariusScanWithoutUsingTypeTagsAsName() {
        val draft = CardDraftParser.parse(
            """
            6
            6
            CHAMPION UNIT DARIUS NOXUS TRIFARIAN
            Darius
            EXECUTIONER
            LEGION - When you play me, ready me.
            Other friendly units have +1U here.
            "They will regret opposing me."
            OGN 243a/298
            / League Splash Team ©2025RGI
            """.trimIndent(),
        )

        assertEquals("Darius - EXECUTIONER", draft.name)
        assertEquals("Champion Unit", draft.cardType)
        assertEquals("OGN", draft.cardSet)
        assertEquals("243a/298", draft.cardNumber)
        assertEquals(
            "LEGION - When you play me, ready me.\n" +
                "Other friendly units have +1 [Might] here.",
            draft.effectText,
        )
    }

    @Test
    fun parsesLegendNameBelowTypeTagLine() {
        val draft = CardDraftParser.parse(
            """
            LEGEND JINX
            Loose Cannon
            At start of your Beginning Phase, draw 1 if you
            have 1 or fewer cards in your hand.
            OGN 251/298
            / Sugar Free ©2025RGI
            """.trimIndent(),
        )

        assertEquals("Loose Cannon", draft.name)
        assertEquals("Legend", draft.cardType)
        assertEquals("OGN", draft.cardSet)
        assertEquals("251/298", draft.cardNumber)
        assertEquals(
            "At start of your Beginning Phase, draw 1 if you\n" +
                "have 1 or fewer cards in your hand.",
            draft.effectText,
        )
    }

    @Test
    fun keepsLoreQuestionOutOfEffectText() {
        val draft = CardDraftParser.parse(
            """
            CHAMPION UNIT EZREAL PILTOVER
            Ezreal
            PRODIGY
            When you play me, discard 1, then draw 2.
            Optional additional costs you pay cost 1 or less.
            Who needs a map?
            SFD 149/221
            / Six More Vodka Â©2025RGI
            """.trimIndent(),
        )

        assertEquals("Ezreal - PRODIGY", draft.name)
        assertEquals(
            "When you play me, discard 1, then draw 2.\n" +
                "Optional additional costs you pay cost 1[Rune] or less.",
            draft.effectText,
        )
    }

    @Test
    fun parsesPowerCostWithAmount() {
        val draft = CardDraftParser.parse(
            """
            Power cost: 2 order
            Darius
            """.trimIndent(),
        )

        assertEquals("2 Order", draft.powerCost)
    }

    @Test
    fun annotatesRuneInNoMoreThanCostText() {
        val effect = CardDraftParser.parseEffectText(
            """
            TANK (I must be assigned combat damage first.)
            When I attack, you may play an Equipment with
            Energy cost no more than 2, ignoring its cost,
            and attach it to me.
            """.trimIndent(),
        )

        assertEquals(
            "TANK (I must be assigned combat damage first.)\n" +
                "When I attack, you may play an Equipment with\n" +
                "Energy cost no more than 2[Rune], ignoring its cost,\n" +
                "and attach it to me.",
            effect,
        )
    }

    @Test
    fun normalizesMisreadRuneLabelsInEffectText() {
        val effect = CardDraftParser.parseEffectText(
            """
            EMPOWER 2[Runo]: Empower me.
            Optional additional costs you pay cost 1[Runa] or less.
            """.trimIndent(),
        )

        assertEquals(
            "EMPOWER 2[Rune]: Empower me.\n" +
                "Optional additional costs you pay cost 1[Rune] or less.",
            effect,
        )
    }

    @Test
    fun treatsMisreadEquipZeroAsSinglePowerSymbol() {
        val effect = CardDraftParser.parseEffectText(
            """
            EQUIP 0> (0: Attach this to a unit you control)
            When I hold, score l point.
            """.trimIndent(),
        )

        assertEquals(
            "EQUIP 1[Power] > (1[Power]: Attach this to a unit you control)\n" +
                "When I hold, score 1 point.",
            effect,
        )
    }

    @Test
    fun doesNotAnnotateZeroAsRuneCost() {
        val effect = CardDraftParser.parseEffectText(
            """
            HIDDEN (Hide now for 0.)
            REACTION (Play on your turn or in showdowns.)
            """.trimIndent(),
        )

        assertEquals(
            "HIDDEN (Hide now for 0.)\n" +
                "REACTION (Play on your turn or in showdowns.)",
            effect,
        )
    }

    @Test
    fun dropsLeadingNoiseAndTrailingLoreFromEffectCrop() {
        val effect = CardDraftParser.parseEffectText(
            """
            NUst De dssiy
            Udllaye
            When I attack, you may play an Equipment with
            Energy cost no more than ignoring its cost,
            and attach it to me.
            Bones shatter and people lie, but l can always count
            on iron,
            """.trimIndent(),
        )

        assertEquals(
            "When I attack, you may play an Equipment with\n" +
                "Energy cost no more than ignoring its cost,\n" +
                "and attach it to me.",
            effect,
        )
    }

    @Test
    fun ignoresDamagedChampionUnitLineWhenParsingNameBand() {
        val draft = CardDraftParser.parse(
            """
            4
            4
            CHAMPIUN UNI
            Rell
            MAGNETIC
            HELL NUXUS
            TANK ( must be assigned combat damage first)
            When I attack, you may play an Equipment with
            Energy cost no more than 2, ignoring its cost,
            and attach it to me.
            SFD 024/221
            """.trimIndent(),
        )

        assertEquals("Rell - MAGNETIC", draft.name)
    }

    @Test
    fun ignoresSeverelyDamagedChampionUnitLineWhenParsingNameBand() {
        val draft = CardDraftParser.parse(
            """
            4
            4
            LAAM PIUN UII
            Rell
            MAGNETIC
            HELL NOXUS
            SFD 024/221
            """.trimIndent(),
        )

        assertEquals("Rell - MAGNETIC", draft.name)
    }

    @Test
    fun ignoresDamagedMightNumberBeforeName() {
        val draft = CardDraftParser.parse(
            """
            4
            b3
            INMIA
            Reluctant Leader
            When you play another unit, give me +2 this turn.
            VEN 121/166 EN
            """.trimIndent(),
        )

        assertEquals("Reluctant Leader", draft.name)
    }

    @Test
    fun recognizesSpellTypeWhenOcrAddsSymbolNoise() {
        val draft = CardDraftParser.parse(
            """
            S) SPELL
            Resonating Strike
            HIDDEN (Hide now for to react with later for 0.)
            VEN 034/166 EN
            """.trimIndent(),
        )

        assertEquals("Spell", draft.cardType)
    }

    @Test
    fun normalizesDrawOneWhenOcrJoinsOneAsLetterL() {
        val effect = CardDraftParser.parseEffectText("When I move, drawl.")

        assertEquals("When I move, draw 1.", effect)
    }

    @Test
    fun normalizesMightSymbolWhenOcrReadsItAsYAfterNumber() {
        val effect = CardDraftParser.parseEffectText("Kill an enemy unit with 3y or less.")

        assertEquals("Kill an enemy unit with 3[Might] or less.", effect)
    }

    @Test
    fun normalizesOneBeforeMightSymbolWhenOcrReadsItAsLetterL() {
        val effect = CardDraftParser.parseEffectText(
            """
            Other friendly units have +l[Might] here.
            It gives an additional -lý this turn.
            """.trimIndent(),
        )

        assertEquals(
            "Other friendly units have +1 [Might] here.\n" +
                "It gives an additional -1 [Might] this turn.",
            effect,
        )
    }
    @Test
    fun normalizesMojibakeMightSymbolsAndStraySlash() {
        val effect = CardDraftParser.parseEffectText("Kill an enemy unit with 3\u00E1\u00BB\u00B7 / or less.")

        assertEquals("Kill an enemy unit with 3[Might] or less.", effect)
    }

    @Test
    fun normalizesSlashBeforeMightAsPlusOneMight() {
        val effect = CardDraftParser.parseEffectText(
            """
            (If it doesn't have a buff, it gets a +/U buff.)
            Draw1.
            """.trimIndent(),
        )

        assertEquals(
            "(If it doesn't have a buff, it gets a +1 [Might] buff.)\n" +
                "Draw 1.",
            effect,
        )
    }

    @Test
    fun normalizesNoisyEquipPowerSymbol() {
        val effect = CardDraftParser.parseEffectText(
            "EOUIP <0) (0>: Attach this to a unit you control.)",
        )

        assertEquals(
            "EQUIP 1[Power] > (1[Power]: Attach this to a unit you control.)",
            effect,
        )
    }
}
