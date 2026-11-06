package dev.ide.store.impl

import dev.ide.platform.JsonReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ai_my_wallet()`'s JSON, as the local database returned it, read into the port's model. */
class AiWalletParseTest {

    private val json = """
        {"models": [{"id": "claude-haiku-5-5", "name": "Claude Haiku 5.5", "premium": false, "available": true, "minPoints": 15},
                    {"id": "claude-sonnet-5-5", "name": "Claude Sonnet 5.5", "premium": true, "available": false, "minPoints": 250}],
         "points": 117, "userId": "9055beda-e434-4a3f-9a04-e103a8bdb04e", "enabled": true, "adPoints": 5,
         "adDailyCap": 8, "freePoints": 17, "adsLeftToday": 6, "earnedPoints": 100, "dailyFreePoints": 30,
         "premiumAvailable": false}
    """.trimIndent()

    @Test
    fun readsEveryField() {
        val w = SupabaseAiCreditsService.parseWallet(JsonReader.parse(json))
        assertEquals("9055beda-e434-4a3f-9a04-e103a8bdb04e", w.userId)
        assertTrue(w.enabled)
        assertEquals(117, w.points)
        assertEquals(17, w.freePoints)
        assertEquals(100, w.earnedPoints)
        assertEquals(30, w.dailyFreePoints)
        assertEquals(5, w.adPoints)
        assertEquals(6, w.adsLeftToday)
        assertEquals(8, w.adDailyCap)
        assertFalse(w.premiumAvailable)
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), w.models.map { it.id })
        assertEquals(250, w.models[1].minPoints)
        assertFalse(w.models[1].available)
    }
}
