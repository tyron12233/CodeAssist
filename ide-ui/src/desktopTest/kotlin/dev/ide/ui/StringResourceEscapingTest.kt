package dev.ide.ui

import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.import_failed
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose Resources is NOT the Android resource compiler: it hands back the XML text as written, so Android's
 * `\'` apostrophe escape — which aapt2 would strip — survives into the UI and users read `module\'s`. Nothing
 * in the pipeline warns about it, so these two tests are the only thing standing between a habit and a
 * user-visible backslash.
 */
class StringResourceEscapingTest {

    /** The semantics, on a real resource: what the UI receives is exactly what the XML says. */
    @Test
    fun apostropheReachesTheUiUnescaped() {
        val text = runBlocking { getString(Res.string.import_failed) }
        assertEquals("Couldn't import the project package.", text)
        assertTrue('\\' !in text, "no backslash should survive into a displayed string: [$text]")
    }

    /**
     * The coverage: every locale, every string. Written against the XML rather than the generated accessors so
     * one test covers all ~1200 strings without naming them — and so a newly-added `\'` fails here rather than
     * shipping.
     */
    @Test
    fun noStringResourceCarriesAnAndroidStyleEscape() {
        // The test task's working directory is the module dir.
        val files = File("src/commonMain/composeResources").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values") }
            ?.mapNotNull { dir -> File(dir, "strings.xml").takeIf { it.isFile } }
            .orEmpty()
        assertTrue(files.isNotEmpty(), "expected to find the composeResources strings.xml files")

        val offenders = files.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> "\\'" in line || "\\\"" in line }
                .map { (i, line) -> "${f.parentFile.name}/strings.xml:${i + 1}: ${line.trim()}" }
        }
        assertTrue(
            offenders.isEmpty(),
            "Android-style escapes are shown literally by Compose Resources — write the character directly:\n" +
                offenders.joinToString("\n"),
        )
    }
}
