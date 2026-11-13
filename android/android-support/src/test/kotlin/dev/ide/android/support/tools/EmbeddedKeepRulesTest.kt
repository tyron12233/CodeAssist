package dev.ide.android.support.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddedKeepRulesTest {

    @Test
    fun legacyRulesApplyWhenNothingTargetsR8() {
        val names = listOf("META-INF/proguard/a.pro", "META-INF/proguard/b.pro", "META-INF/MANIFEST.MF", "com/x/Y.class")
        assertEquals(listOf("META-INF/proguard/a.pro", "META-INF/proguard/b.pro"), EmbeddedKeepRules.applicable(names))
    }

    @Test
    fun targetedRulesReplaceLegacyAndFollowTheVersionRange() {
        val names = listOf(
            "META-INF/proguard/legacy.pro",
            "META-INF/com.android.tools/proguard/pg.pro",
            "META-INF/com.android.tools/r8-upto-8.0.0/old.pro",
            "META-INF/com.android.tools/r8-from-8.0.0/current.pro",
            "META-INF/com.android.tools/r8-from-8.0.0-upto-8.13.0/just-missed.pro",
            "META-INF/com.android.tools/r8-from-99.0.0/future.pro",
            "META-INF/com.android.tools/r8/always.pro",
        )
        assertEquals(
            listOf("META-INF/com.android.tools/r8-from-8.0.0/current.pro", "META-INF/com.android.tools/r8/always.pro"),
            EmbeddedKeepRules.applicable(names),
        )
    }
}
