package dev.ide.core

import dev.ide.model.LanguageLevel
import dev.ide.model.impl.jdk.JdkSdkProvider
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Swing Create-Project templates must produce source that actually compiles, so a typo in a template can
 * never ship. They are created exactly as the Create-Project flow does and then built.
 *
 * The SDK is pinned to the running JDK rather than [IdeServices.defaultDesktopSdk], which prefers an installed
 * Android SDK: `android.jar` has no `javax.swing`, so it cannot compile a Swing program. Making these
 * templates work against an Android platform SDK needs the toolkit's API as a compile-time stub jar, which is
 * a separate piece of work; this test covers the template source itself.
 *
 * Compilation is all that is asserted. Running them needs a display, and the test JVMs are headless on
 * purpose; the device path is covered by the ART spikes instead.
 */
class SwingTemplatesTest {

    private fun compile(templateId: String) {
        withTempDir("swing-$templateId") { dir ->
            IdeServices.createProjectAt(
                dir, templateId, mapOf(TemplateArgs.NAME to templateId),
                JdkSdkProvider.detect(), LanguageLevel.JAVA_17,
            ).use { ide ->
                val capture = runBlocking { ide.runAndCapture("app") }
                assertTrue(
                    capture.compiled,
                    "$templateId should compile; diagnostics=${capture.diagnostics}, stdout=${capture.stdout}",
                )
            }
        }
    }

    @Test fun theSwingAppTemplateCompiles() {
        compile("swing-app")
    }

    @Test fun theSwingCustomPaintingTemplateCompiles() {
        compile("swing-canvas")
    }
}
