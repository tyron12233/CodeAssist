package dev.ide.core

import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `res/raw/` holds verbatim assets (aapt2 copies them, it never compiles them as resources), so a raw file must
 * not be analysed as source: a `.txt` there gets NO diagnostics at all (parsed as Java, the host's old fallback
 * language, it came back full of syntax and unresolved-symbol errors), and a raw `.xml` gets well-formedness
 * only, never the Android schema / resource-reference checks.
 */
class RawResourceFileTypeTest {

    private fun rawDir(root: Path): Path =
        root.resolve("app/src/main/res/raw").also { Files.createDirectories(it) }

    @Test
    fun rawTextFileGetsNoDiagnostics() {
        withTempDir("ide-raw-filetype") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val notes = rawDir(dir).resolve("notes.txt")
                val text = "hello < world & this is not xml\nnor is it java;\n"
                Files.writeString(notes, text)

                val diags = runBlocking { ide.analyzeDiagnostics(notes, text) }
                assertTrue(diags.isEmpty(), "a res/raw/*.txt file must not be analysed, but got: $diags")

                // Nor served by a language backend: completion offers buffer words only, no Java members.
                val items = runBlocking { ide.complete(notes, text, text.indexOf("world") + 3) }.items
                assertTrue(
                    items.all { it.container == null },
                    "plain-text completion should not resolve symbols, but got: ${items.map { it.label }}",
                )
            }
        }
    }

    @Test
    fun rawXmlKeepsWellFormednessButNotAndroidChecks() {
        withTempDir("ide-raw-xml") { dir ->
            IdeServices.bootstrapDemo(dir).use { ide ->
                val config = rawDir(dir).resolve("config.xml")
                // Arbitrary data XML: `@string/nope` is plain text here, and `count` is no Android attribute.
                val text = "<config count=\"3\">\n  <entry>@string/nope</entry>\n</config>\n"
                Files.writeString(config, text)

                val diags = runBlocking { ide.analyzeDiagnostics(config, text) }
                assertTrue(
                    diags.none { it.code?.startsWith("android.") == true },
                    "a res/raw/*.xml asset must not get the Android resource checks, but got: $diags",
                )

                // Well-formedness still applies: an unclosed tag is reported.
                val broken = "<config>\n  <entry>data\n"
                Files.writeString(config, broken)
                val brokenDiags = runBlocking { ide.analyzeDiagnostics(config, broken) }
                assertTrue(
                    brokenDiags.isNotEmpty(),
                    "malformed raw XML should still report well-formedness diagnostics",
                )
            }
        }
    }
}
