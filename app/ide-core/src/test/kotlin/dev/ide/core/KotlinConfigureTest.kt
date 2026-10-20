package dev.ide.core

import dev.ide.testkit.writeSource
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Configuring Kotlin for a module (the editor's "Kotlin isn't configured" prompt): a Java module that just got
 * its first `.kt` file has no Kotlin standard library until the next build or open adds it, and the prompt's
 * action adds it at once, to that module only. With it, Java code there can use what Kotlin compiles to.
 */
class KotlinConfigureTest {

    private val root = createTempDirectory("kotlin-configure")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    private fun awaitIndexQuiet(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline && System.currentTimeMillis() - quietSince < 1_000) {
            val st = s.indexService.status
            if (!st.ready || st.building) quietSince = System.currentTimeMillis()
            Thread.sleep(50)
        }
    }

    @Test
    fun aJavaModuleWithItsFirstKotlinFileIsConfiguredOnRequest() {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        val kt = root.resolve("core/src/main/java/com/example/core/Clicker.kt")
        root.writeSource(
            "core/src/main/java/com/example/core/Clicker.kt",
            "package com.example.core\nclass Clicker { fun onClick(listener: (String) -> Unit) {} }\n",
            trim = false,
        )
        s.invalidateSyntheticClasses()
        val core = s.modules().single { it.name == "core" }
        assertFalse(s.kotlinConfigured(core), "the first .kt file doesn't bring the stdlib with it")

        val before = s.indexService.status.stats
        assertTrue(s.configureKotlin(s.moduleForFile(kt)!!), "configuring adds the bundled stdlib")
        assertTrue(s.kotlinConfigured(s.modules().single { it.name == "core" }))
        assertFalse(
            s.kotlinConfigured(s.modules().single { it.name == "app" }),
            "only the module the prompt was for: app has no Kotlin",
        )

        // The stdlib re-syncs the index; then Java in the module sees Kotlin's lambda type for what it is.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && s.indexService.status.stats === before) Thread.sleep(50)
        awaitIndexQuiet(s)
        val probe = root.resolve("core/src/main/java/com/example/core/Probe.java")
        val text = "package com.example.core;\nclass Probe { void m() { new Clicker().onClick(t -> { t.length(); return null; }); } }"
        val errs = runBlocking { s.analyzeDiagnostics(probe, text) }.filter { it.severity.name == "ERROR" }.map { it.message }
        assertTrue(errs.isEmpty(), "the lambda's parameter is a String once Kotlin is configured: $errs")
    }
}
