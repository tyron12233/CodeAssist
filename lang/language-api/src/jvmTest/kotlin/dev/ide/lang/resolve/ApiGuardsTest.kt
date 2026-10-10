package dev.ide.lang.resolve

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiGuardsTest {

    @Test
    fun comparisonsGuaranteeTheirLevelOnTheRightOutcome() {
        assertEquals(26, ApiGuards.levelGuaranteed("Build.VERSION.SDK_INT >= 26", true))
        assertEquals(0, ApiGuards.levelGuaranteed("Build.VERSION.SDK_INT >= 26", false))
        assertEquals(27, ApiGuards.levelGuaranteed("android.os.Build.VERSION.SDK_INT > 26", true))
        assertEquals(26, ApiGuards.levelGuaranteed("SDK_INT < Build.VERSION_CODES.O", false))
        assertEquals(0, ApiGuards.levelGuaranteed("SDK_INT < Build.VERSION_CODES.O", true))
        assertEquals(27, ApiGuards.levelGuaranteed("VERSION.SDK_INT <= 26", false))
        assertEquals(26, ApiGuards.levelGuaranteed("26 <= Build.VERSION.SDK_INT", true))
        assertEquals(33, ApiGuards.levelGuaranteed("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU", true))
    }

    @Test
    fun conjunctionsNegationAndParenthesesCombine() {
        assertEquals(30, ApiGuards.levelGuaranteed("(SDK_INT >= 26) && ready && SDK_INT >= 30", true))
        assertEquals(0, ApiGuards.levelGuaranteed("SDK_INT >= 26 && ready", false))
        assertEquals(26, ApiGuards.levelGuaranteed("SDK_INT < 26 || legacy", false))
        assertEquals(0, ApiGuards.levelGuaranteed("SDK_INT >= 26 || legacy", true))
        assertEquals(26, ApiGuards.levelGuaranteed("!(SDK_INT < 26)", true))
        assertEquals(26, ApiGuards.levelGuaranteed("((SDK_INT >= 26))", true))
    }

    @Test
    fun unrelatedConditionsGuaranteeNothing() {
        assertEquals(0, ApiGuards.levelGuaranteed("x >= 26", true))
        assertEquals(0, ApiGuards.levelGuaranteed("SDK_INT >= MY_LEVEL", true))
        assertEquals(0, ApiGuards.levelGuaranteed("isReady()", true))
    }

    @Test
    fun annotationsReadValueAndApiArguments() {
        assertEquals(26, ApiGuards.levelFromAnnotation("RequiresApi", "26"))
        assertEquals(26, ApiGuards.levelFromAnnotation("androidx.annotation.RequiresApi", "api = Build.VERSION_CODES.O"))
        assertEquals(24, ApiGuards.levelFromAnnotation("TargetApi", "Build.VERSION_CODES.N"))
        assertEquals(0, ApiGuards.levelFromAnnotation("Deprecated", "26"))
        assertEquals(0, ApiGuards.levelFromAnnotation("RequiresApi", null))
    }

    @Test
    fun docLineIsPrependedToPlainAndRawDocs() {
        val level = ApiLevel(26, deprecatedIn = 28)
        assertEquals("Added in API 26 · Deprecated in API 28", level.prependTo(null))
        assertEquals("Added in API 26 · Deprecated in API 28\n\nCreates a channel.", level.prependTo("Creates a channel."))
        assertEquals(
            "/**\n * Added in API 26 · Deprecated in API 28\n *\n * Creates a channel.\n * @param id the id\n */",
            level.prependTo("/** Creates a channel.\n * @param id the id\n */"),
        )
        assertEquals("Added in API 1", ApiLevel(0).describe())
    }
}
