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
}
