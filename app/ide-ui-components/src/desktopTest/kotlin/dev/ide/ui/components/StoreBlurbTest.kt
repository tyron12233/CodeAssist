package dev.ide.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** The inputs are real descriptions from the live catalogue, which is where every rule here came from. */
class StoreBlurbTest {

    @Test
    fun dropsPastedHeaderLines() = assertEquals(
        "Includes background music, theme mode.",
        storeBlurb("App Name: Marble Bluff\nVersion: 1.0\nIncludes background music, theme mode.", "Marble Bluff"),
    )

    @Test
    fun dropsAHeadingThatRepeatsTheTitle() = assertEquals(
        "Turn your project into a single file.",
        storeBlurb("# One Code For AI\n\nTurn your project into a **single** file.", "One Code For AI"),
    )

    @Test
    fun keepsAHeadingThatSaysSomething() = assertEquals(
        "Features Play.",
        storeBlurb("## Features\n- Play.", "Maze Rush"),
    )

    @Test
    fun dropsBracketedLanguageLabels() = assertEquals(
        "v1.1.4 每天一签",
        storeBlurb("【中文】\nv1.1.4\n每天一签", "DailyFortune"),
    )

    @Test
    fun fallsBackWhenNothingSurvives() = assertEquals(
        "Resource helper",
        storeBlurb("1.", "Resource Tool", fallback = "Resource helper"),
    )

    @Test
    fun plainProseIsUntouched() = assertEquals(
        "Talk like you mean it.",
        storeBlurb("Talk like you mean it.", "Gochat"),
    )
}
