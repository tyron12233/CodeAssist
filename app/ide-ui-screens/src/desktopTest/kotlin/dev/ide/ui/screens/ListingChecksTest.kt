package dev.ide.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The listings here are real submissions the review queue has seen, which is what the rules were tuned on. */
class ListingChecksTest {

    @Test
    fun portugueseIsCaught() = assertTrue(
        looksNonEnglish(
            "DeadLine é um jogo arcade com temática cyberpunk, vc consegue clicar em todos os alvos sem " +
                "perder? Veremos, nesse jogo vc terá alguns alvos até chegar no seu modo sobrecarga",
        ),
    )

    @Test
    fun aNonLatinScriptIsCaught() = assertTrue(looksNonEnglish("每天一签，为你提供每日运势与生活指引的极简占卜应用。打开 App"))

    @Test
    fun englishIsNotFlagged() = assertFalse(
        looksNonEnglish(
            "Deadline is a cyberpunk-themed arcade game. Can you click on all the targets without losing? " +
                "You will have permanent and temporary abilities to help you.",
        ),
    )

    @Test
    fun anEnglishListingNamingAForeignPlaceIsNotFlagged() = assertFalse(
        looksNonEnglish("A weather app with forecasts for every city, from São Paulo to Bandung, built in Compose."),
    )

    @Test
    fun shortTextIsNeverJudged() = assertFalse(looksNonEnglish("Juego"))

    @Test
    fun weakSummaries() {
        assertTrue(isWeakSummary("Dead Line", "Game Arcade"))
        assertTrue(isWeakSummary("Gochat", "gochat"))
        assertFalse(isWeakSummary("Gochat", "Chat with friends over Bluetooth, no internet needed."))
        // Blank is the form's own "required" message, not a second warning on top of it.
        assertFalse(isWeakSummary("Gochat", ""))
    }

    @Test
    fun theDialogListsEveryStandingIssue() = assertEquals(
        listOf(ListingIssue.NotEnglish, ListingIssue.WeakSummary, ListingIssue.Spammy),
        listingIssues("Jogo", "Jogo", "Download free! Um jogo para você com uma história que não acaba"),
    )

    @Test
    fun aCleanListingHasNone() = assertEquals(
        emptyList(),
        listingIssues("Nimbus Weather", "A weather app built with Jetpack Compose", "Forecasts, radar and alerts, all offline-first."),
    )
}
