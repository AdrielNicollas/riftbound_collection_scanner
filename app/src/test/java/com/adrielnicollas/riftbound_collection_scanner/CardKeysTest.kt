package com.adrielnicollas.riftbound_collection_scanner

import com.adrielnicollas.riftbound_collection_scanner.data.CardKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class CardKeysTest {
    @Test
    fun buildsStableKeyFromNameAndNumber() {
        val key = CardKeys.build("Ahri, Inquisitive!", " OGN - 123 ")

        assertEquals("ahri inquisitive|OGN-123", key)
    }

    @Test
    fun stripsAccentsAndUsesNameWhenNumberIsBlank() {
        val key = CardKeys.build("Disciplína Arcána", "")

        assertEquals("disciplina arcana", key)
    }
}
