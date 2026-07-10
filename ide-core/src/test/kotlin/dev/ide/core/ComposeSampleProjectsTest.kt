package dev.ide.core

import dev.ide.android.support.templates.Game2048SampleTemplate
import dev.ide.android.support.templates.MemoryMatchSampleTemplate
import dev.ide.android.support.templates.SnakeSampleTemplate
import dev.ide.android.support.templates.TicTacToeSampleTemplate
import dev.ide.model.LanguageLevel
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateArgs
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the bundled **Jetpack Compose sample games** scaffold correctly — created from their templates
 * exactly as the store's "Create project" flow does. Generation needs no Android SDK, so this runs on any
 * host: it checks each sample lays down its Compose sources + manifest, turns the Compose compiler on in the
 * module, and declares the Compose runtime dependencies. If a sample stops generating, this fails the build.
 */
class ComposeSampleProjectsTest {

    private data class SampleCase(
        val template: ProjectTemplate,
        val pkgPath: String,
        val gameFile: String,
    )

    private val cases = listOf(
        SampleCase(SnakeSampleTemplate, "com/example/snake", "SnakeGame.kt"),
        SampleCase(TicTacToeSampleTemplate, "com/example/tictactoe", "TicTacToeGame.kt"),
        SampleCase(MemoryMatchSampleTemplate, "com/example/memory", "MemoryGame.kt"),
        SampleCase(Game2048SampleTemplate, "com/example/game2048", "Game2048.kt"),
    )

    @Test
    fun composeSamplesGenerateExpectedFiles() {
        for (case in cases) {
            val id = case.template.id.value
            val dir = Files.createTempDirectory("compose-sample-$id")
            try {
                IdeServices.createProjectAt(
                    dir, id, mapOf(TemplateArgs.NAME to id),
                    IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
                ).use {
                    val base = dir.resolve("app/src/main/kotlin/${case.pkgPath}")
                    val main = base.resolve("MainActivity.kt")
                    assertTrue(Files.exists(main), "$id: MainActivity.kt missing")
                    assertTrue(Files.exists(base.resolve(case.gameFile)), "$id: ${case.gameFile} missing")
                    assertTrue(Files.exists(dir.resolve("README.md")), "$id: README.md missing")
                    assertTrue(
                        Files.exists(dir.resolve("app/src/main/AndroidManifest.xml")),
                        "$id: AndroidManifest.xml missing",
                    )

                    val mainText = main.readText()
                    assertTrue("setContent" in mainText, "$id: MainActivity should call setContent")
                    assertTrue("@Composable" in mainText, "$id: MainActivity should declare a @Composable")

                    // The Compose compiler must be enabled on the generated module.
                    val moduleTomls = dir.toFile().walkTopDown()
                        .filter { it.name == "module.toml" }
                        .map { it.readText() }
                        .toList()
                    assertTrue(
                        moduleTomls.any { "compose = true" in it },
                        "$id: a module.toml should enable Compose (compose = true)",
                    )

                    // A game-themed launcher icon: a valid foreground vector + a custom background color.
                    val foreground = dir.resolve("app/src/main/res/drawable/ic_launcher_foreground.xml")
                    assertTrue(Files.exists(foreground), "$id: launcher foreground icon missing")
                    val fgText = foreground.readText()
                    assertTrue(fgText.startsWith("<?xml"), "$id: icon XML declaration must start the file")
                    assertTrue("<vector" in fgText && "<path" in fgText, "$id: icon should be a vector with paths")
                    assertTrue(
                        "ic_launcher_background" in dir.resolve("app/src/main/res/values/colors.xml").readText(),
                        "$id: launcher background color missing",
                    )
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun composeSamplesDeclareComposeDependencies() {
        for (case in cases) {
            val id = case.template.id.value
            val deps = case.template.dependencies(TemplateArgs(mapOf(TemplateArgs.NAME to id)))
            val coordinates = deps.map { it.coordinate }
            assertTrue(deps.isNotEmpty() && deps.all { it.module == "app" }, "$id: deps should attach to the app module")
            assertTrue(
                coordinates.any { it.startsWith("androidx.compose.material3:material3") },
                "$id: should declare Compose Material 3; got $coordinates",
            )
            assertTrue(
                coordinates.any { it.startsWith("androidx.activity:activity-compose") },
                "$id: should declare activity-compose; got $coordinates",
            )
        }
    }
}
