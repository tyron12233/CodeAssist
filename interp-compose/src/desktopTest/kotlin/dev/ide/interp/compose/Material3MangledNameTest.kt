package dev.ide.interp.compose

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for the field bug `no static Text(1) on androidx.compose.material3.TextKt`: Material3's `Text`
 * has inline value-class parameters (`Color`/`TextUnit`), so Kotlin MANGLES its JVM name to `Text-<hash>`
 * (e.g. `Text-Nvy7gAk`). The resolver only knows the Kotlin name `Text`, so every name match must accept the
 * mangled form. This runs against the REAL Material3 on the desktop test classpath.
 */
class Material3MangledNameTest {

    @Test
    fun realMaterial3TextIsDetectedDespiteNameMangling() {
        // `Text` is compiled to `Text-<hash>` (value-class params) — no JVM method is literally named `Text`,
        // yet the composer-threading path must still recognize it.
        assertTrue(
            ComposableAbi.isComposableCall("androidx.compose.material3.TextKt", "Text"),
            "Material3 `Text` (mangled to `Text-<hash>`) must be detected as composable",
        )
        // And a couple of other mangled Material3 composables, to be safe.
        assertTrue(ComposableAbi.isComposableCall("androidx.compose.material3.ButtonKt", "Button"), "Button")
    }
}
