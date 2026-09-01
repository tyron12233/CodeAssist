package dev.ide.core

import dev.ide.model.LanguageLevel
import dev.ide.model.template.TemplateArgs
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Swing Create-Project templates must produce source that actually compiles, so a typo in a template can
 * never ship. They are created exactly as the Create-Project flow does and then built.
 *
 * It uses [IdeServices.defaultDesktopSdk], the SDK a real desktop project gets. On the desktop that resolves
 * `javax.swing` whether or not the platform SDK carries it, because there is a JDK to fall back on; the case
 * the bundled API jar (`SwingApiStubs`) actually exists for is the device, where `android.jar` has no Swing
 * and there is no JDK behind it. That side is covered by `SwingApiStubsArtSpike`.
 *
 * Compilation is all that is asserted. Running them needs a display, and the test JVMs are headless on
 * purpose; the device path is covered by the ART spikes instead.
 */
class SwingTemplatesTest {

    private fun compile(templateId: String) {
        withTempDir("swing-$templateId") { dir ->
            IdeServices.createProjectAt(
                dir, templateId, mapOf(TemplateArgs.NAME to templateId),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
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
