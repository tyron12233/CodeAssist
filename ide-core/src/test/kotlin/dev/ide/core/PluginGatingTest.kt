package dev.ide.core

import dev.ide.lang.LANGUAGE_BACKEND_EP
import dev.ide.lang.java.JavaLanguageBackend
import dev.ide.lang.kotlin.KotlinLanguageBackend
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ApplicationEnvironment] loads only the ENABLED built-in plugins (per its [dev.ide.plugin.impl.PluginCatalog]):
 * a disabled plugin's contributions are absent, its dependents drop with it, and an essential plugin cannot be
 * disabled. The disabled set is applied at construction — i.e. at the next launch — matching restart-apply.
 */
class PluginGatingTest {

    private fun backends(env: ApplicationEnvironment) =
        env.platform.extensions.extensions(LANGUAGE_BACKEND_EP).map { it::class }

    @Test
    fun `all plugins enabled by default`() {
        ApplicationEnvironment().use { env ->
            assertTrue(env.pluginCatalog.isEnabled("kotlin-language"))
            assertTrue(backends(env).contains(KotlinLanguageBackend::class), "kotlin backend present by default")
        }
    }

    @Test
    fun `disabling a plugin drops its contributions and dependents`() {
        ApplicationEnvironment(disabledPluginIds = setOf("kotlin-language")).use { env ->
            assertFalse(env.pluginCatalog.isEnabled("kotlin-language"))
            assertFalse(backends(env).contains(KotlinLanguageBackend::class), "kotlin backend gone when disabled")
            // kotlin-analysis dependsOn kotlin-language, so it drops too — no dangling load edge.
            assertFalse(env.pluginCatalog.isEnabled("kotlin-analysis"))
            // The essential Java (IntelliJ-PSI) editor backend is untouched.
            assertTrue(backends(env).contains(JavaLanguageBackend::class))
        }
    }

    @Test
    fun `an essential plugin cannot be disabled`() {
        // java-psi-language is the essential `.java` editor backend + the LANGUAGE_BACKEND_EP resolution
        // fallback; it (and the jdt-language file-type/compiler owner it depends on) cannot be disabled.
        ApplicationEnvironment(disabledPluginIds = setOf("java-psi-language", "jdt-language")).use { env ->
            assertTrue(env.pluginCatalog.isEnabled("jdt-language"), "jdt-language is essential")
            assertTrue(env.pluginCatalog.isEnabled("java-psi-language"), "java-psi-language is essential")
            assertTrue(backends(env).contains(JavaLanguageBackend::class), "the fallback backend stays loaded")
        }
    }
}
