package dev.ide.interp

import dev.ide.lang.kotlin.interp.KotlinPreviewLowering
import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.parse.KotlinParserHost
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.testkit.TestJars
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The cross-file half of [EnumEntryShadowingJdkClassTest]: the enum lives in one file and the caller in
 * another, which is what a real project does (a design-system module defines the theme enum, a screen
 * elsewhere reads it).
 */
class EnumEntryShadowingCrossFileTest {

    @Test
    fun aWhenOverAnEnumFromAnotherFileBindsItsOwnEntry() {
        assertEquals(true, runShadowedEnum("System", "Light", "Dark"))
    }

    @Test
    fun theQualifiedSpellingOfTheSameBranchAlsoBinds() {
        // The workaround a project can apply without waiting for a release: `DarkMode.System ->` never goes
        // through bare-name resolution at all, so it was unaffected by the collision.
        assertEquals(true, runShadowedEnum("DarkMode.System", "DarkMode.Light", "DarkMode.Dark"))
    }

    private fun runShadowedEnum(sys: String, light: String, dark: String): Any? {
        val dir = Files.createTempDirectory("enum-shadow-xfile")
        fun write(rel: String, text: String) {
            val f = dir.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, text)
        }
        write(
            "theme/ThemeSettings.kt",
            """
            package demo.theme

            enum class DarkMode {
                System,
                Light,
                Dark;

                fun resolve(systemIsDark: Boolean): Boolean = when (this) {
                    $sys -> systemIsDark
                    $light -> false
                    $dark -> true
                }
            }

            data class ThemeSettings(val darkMode: DarkMode = DarkMode.System)
            """.trimIndent() + "\n",
        )
        val entryCode = """
            package demo.ui

            import demo.theme.DarkMode
            import demo.theme.ThemeSettings

            fun box(): Boolean = ThemeSettings().darkMode.resolve(true)
        """.trimIndent() + "\n"
        write("ui/Screen.kt", entryCode)

        // `java.lang.System` has to be RESOLVABLE for the collision to exist at all: on device android.jar
        // supplies it, and a stdlib-only classpath silently makes this test vacuous.
        val javaBase = TestJars.jdkBaseJar(Paths.get("build/tmp/testkit-jdk"))
        assumeTrue(javaBase != null, "needs a readable runtime image to put java.lang.System on the classpath")
        val service = KotlinSymbolService(
            listOf(DiskFile(dir)),
            listOf(stdlibJarPath().toString(), javaBase!!.toString()),
        )
        val entryFile = dir.resolve("ui/Screen.kt")
        val kt = KotlinParserHost.parse("Screen.kt", entryCode)
        val parsed = KotlinParsedFile(kt, DiskFile(entryFile), 0)
        val model = KotlinPreviewLowering(service).crossFileModel(parsed)
        val diags = model.program.values.flatMap { f -> f.diagnostics.map { "${f.name}: ${it.reason}" } }
        assertTrue(diags.isEmpty(), "every function must lower cleanly; diags=$diags")

        return Interpreter(model.program, classes = model.classes)
            .call(model.program.getValue("box/0"), emptyList())
    }
}
