package dev.ide.core

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.FacetCodecRegistry
import dev.ide.model.impl.ModuleTypeRegistry
import dev.ide.model.impl.ProjectModel
import dev.ide.lang.hints.InlayHintKind
import dev.ide.model.LanguageLevel
import dev.ide.model.template.TemplateArgs
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises [IdeServices.runAndCapture] — the compile + run + stdout-capture seam the Learn exercise checker
 * ([dev.ide.core.backend.LearnBackend.check]) runs its scratch project through. Proves a passing exercise's
 * output is captured (compiled + exit 0 + stdout) and that a compile error is reported without a run.
 */
class LearnRunCaptureTest {

    private class JavaLib : ModuleType {
        override val id = "java-lib"
        override val displayName = "Java Library"
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE)
    }

    private fun workspace(dir: Path, mainJava: String) {
        val platform = PlatformCore()
        try {
            ModuleTypeRegistry(platform.extensions).register(JavaLib(), PluginId("java-support"))
            val store = ProjectModel.open(dir, platform, FacetCodecRegistry())
            val javaLib = ModuleTypeRegistry(platform.extensions).resolve("java-lib")
            store.workspace.beginModification().apply {
                addProject("learn", BuildSystemId.NATIVE, store.vfs.root()); commit()
            }
            val mainSet = SourceSetTemplate(
                "main", DependencyScope.IMPLEMENTATION, mapOf("src/main/java" to setOf(ContentRole.SOURCE)),
            )
            store.workspace.projects.single().beginModification().apply {
                addModule("app", javaLib).addSourceSet(mainSet); commit()
            }
            // Default package (no `package` line) — how the Learn checker writes an exercise's Main.
            val f = dir.resolve("app/src/main/java/Main.java")
            Files.createDirectories(f.parent)
            Files.writeString(f, mainJava)
            store.save()
        } finally {
            platform.dispose()
        }
    }

    @Test
    fun capturesOutputOfAPassingExercise() {
        val dir = Files.createTempDirectory("learn-run-ok")
        workspace(
            dir,
            """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("LEARN_RUN_OK");
                }
            }
            """.trimIndent(),
        )
        IdeServices.open(dir).use { ide ->
            val cap = runBlocking { ide.runAndCapture("app") }
            assertTrue(cap.compiled, "should compile + run; diagnostics=${cap.diagnostics}, stdout=${cap.stdout}")
            assertEquals(0, cap.exitCode, "should exit 0; stdout=${cap.stdout}")
            assertTrue("LEARN_RUN_OK" in cap.stdout, "captured stdout should hold the marker; was: ${cap.stdout}")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun computesInlayHintsForALessonBuffer() {
        // The lesson editor's inlay hints (LearnService.hints → LearnBackend.hints) delegate to
        // IdeServices.inlayHints over the scratch module's default-package Main — the same shape this workspace
        // builds. A `var` in the live buffer must get its inferred type hinted.
        val dir = Files.createTempDirectory("learn-hints")
        val code = """
            public class Main {
                public static void main(String[] args) {
                    var greeting = "hi";
                    System.out.println(greeting);
                }
            }
        """.trimIndent()
        workspace(dir, code)
        IdeServices.open(dir).use { ide ->
            val main = dir.resolve("app/src/main/java/Main.java")
            val hints = ide.inlayHints(main, code, 0, code.length)
            val typeHint = hints.firstOrNull { it.kind == InlayHintKind.TYPE }
            assertNotNull(typeHint, "expected a var type hint for the lesson buffer; got $hints")
            assertTrue(
                typeHint.parts.joinToString("") { it.text }.contains("String"),
                "var greeting should be inferred String; got ${typeHint.parts}",
            )
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runsTheOnDiskMainNotAStaleIndexedFqn() {
        // Reproduces the real Learn-scratch flow: the scratch is created from the `java-console` TEMPLATE, whose
        // Main lives in a package (`com.example.app.Main`) — so the entry-point index records that FQN. The Learn
        // checker then overwrites the scratch's Main with a DEFAULT-PACKAGE class written straight to disk
        // (deleting the packaged source, bypassing the save→reindex path), so the index now names a since-deleted
        // class. runAndCapture must launch the on-disk `Main`, not the stale `com.example.app.Main` (which failed
        // on device with "Could not find or load main class com.example.app.Main").
        val dir = Files.createTempDirectory("learn-stale-main")
        try {
            IdeServices.createProjectAt(
                dir, "java-console", mapOf(TemplateArgs.NAME to "learn"),
                IdeServices.defaultDesktopSdk(), LanguageLevel.JAVA_17,
            ).use { ide ->
                // Let the entry-point index build over the template's packaged Main, so detection would otherwise
                // trust the (soon-to-be-stale) index entry rather than fall back to a live scan.
                runBlocking { withTimeoutOrNull(30_000) { ide.indexStatus.first { !it.building } } }

                val srcRoot = dir.resolve("app/src/main/java")
                Files.walk(srcRoot).use { s ->
                    s.filter { Files.isRegularFile(it) && it.toString().endsWith(".java") }.collect(Collectors.toList())
                }.forEach { Files.delete(it) }
                Files.writeString(
                    srcRoot.resolve("Main.java"),
                    """
                    public class Main {
                        public static void main(String[] args) {
                            System.out.println("STALE_INDEX_OK");
                        }
                    }
                    """.trimIndent(),
                )

                val cap = runBlocking { ide.runAndCapture("app") }
                assertTrue(cap.compiled, "should compile + run the on-disk default-package Main; diagnostics=${cap.diagnostics}, stdout=${cap.stdout}")
                assertEquals(0, cap.exitCode, "should exit 0; stdout=${cap.stdout}, diagnostics=${cap.diagnostics}")
                assertTrue(
                    "STALE_INDEX_OK" in cap.stdout,
                    "should run the on-disk Main, not a stale com.example.app.Main; stdout=${cap.stdout}, diagnostics=${cap.diagnostics}",
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun reportsCompileErrorsWithoutRunning() {
        val dir = Files.createTempDirectory("learn-run-err")
        workspace(
            dir,
            """
            public class Main {
                public static void main(String[] args) {
                    System.out.println(nope)
                }
            }
            """.trimIndent(),
        )
        IdeServices.open(dir).use { ide ->
            val cap = runBlocking { ide.runAndCapture("app") }
            assertFalse(cap.compiled, "code with an error must not be reported as compiled; stdout=${cap.stdout}")
            assertTrue(cap.diagnostics.isNotEmpty(), "a compile failure should surface diagnostics")
        }
        dir.toFile().deleteRecursively()
    }
}
