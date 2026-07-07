package dev.ide.core

import kotlinx.coroutines.runBlocking

import dev.ide.ui.backend.RunStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headless test of the IDE backend the Compose UI drives: bootstrap the demo, then complete + build/run. */
class IdeServicesTest {

    @Test
    fun runBuildCompilesAndRunsTheConsoleApp() {
        val dir = Files.createTempDirectory("ide-run")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            ide.build.runBuild()
            awaitBuild(ide)
            val state = ide.build.buildState.value
            assertEquals(RunStatus.Succeeded, state.status, "build/run failed; log:\n${state.log.joinToString("\n")}")
            assertTrue(state.steps.any { it.name.endsWith(":run") }, "expected a run step: ${state.steps.map { it.name }}")
            // A console run's program stdout streams to the interactive run console (the full-screen Run
            // terminal), not the build log — which now carries only the compile/run task lines.
            val transcript = ide.build.runConsole.value?.transcript?.joinToString("") { it.text } ?: ""
            assertTrue("HELLO, WORLD!" in transcript, "expected program output in the run console:\n$transcript")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runBuildReflectsUnsavedEditorChanges() {
        val dir = Files.createTempDirectory("ide-edit")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val main = ide.sourceRoots(app).first().resolve("com/example/app/Main.java")
            // edit only the in-memory buffer (no save to disk)
            ide.updateDocument(
                main,
                "package com.example.app;\npublic class Main { public static void main(String[] a) { System.out.println(\"BUFFER EDIT 12345\"); } }",
            )
            ide.build.runBuild()
            awaitBuild(ide)
            val state = ide.build.buildState.value
            assertEquals(RunStatus.Succeeded, state.status, "log:\n${state.log.joinToString("\n")}")
            val transcript = ide.build.runConsole.value?.transcript?.joinToString("") { it.text } ?: ""
            assertTrue("BUFFER EDIT 12345" in transcript, "unsaved edit not compiled/run:\n$transcript")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runBuildFailsOnACompileError() {
        val dir = Files.createTempDirectory("ide-err")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val main = ide.sourceRoots(app).first().resolve("com/example/app/Main.java")
            ide.updateDocument(
                main,
                "package com.example.app;\npublic class Main { public static void main(String[] a) { int x = ; } }",
            )
            ide.build.runBuild()
            awaitBuild(ide)
            assertEquals(RunStatus.Failed, ide.build.buildState.value.status, "a compile error must fail the build")
        }
        dir.toFile().deleteRecursively()
    }

    private fun awaitBuild(ide: IdeServices, timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (ide.build.buildState.value.status == RunStatus.Running && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun bootstrapsDemoProjectWithModulesAndSources() {
        val dir = Files.createTempDirectory("ide-demo")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            assertEquals(setOf("core", "util", "app"), ide.modules().map { it.name }.toSet())
            val core = ide.modules().first { it.name == "core" }
            val greeter = ide.sourceRoots(core).first().resolve("com/example/core/Greeter.java")
            assertTrue(Files.exists(greeter), "sample source should be written to disk")
            assertTrue(Files.exists(dir.resolve(".platform/workspace.json")), "model should be persisted")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun completesDirectTransitiveAndPlatformMembers() {
        val dir = Files.createTempDirectory("ide-demo")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            // A probe file in :app's source root (JDT requires the unit name to match the class).
            val probe = ide.sourceRoots(app).first().resolve("com/example/app/Probe.java")

            // Direct dependency (:util): complete members of a Formatter.
            labelsAt(
                ide, probe,
                "package com.example.app; import com.example.util.Formatter; class Probe { void m(){ Formatter f = new Formatter(); f.|CARET| } }",
            ).let { l -> assertTrue(l.containsAll(listOf("format", "banner")), "cross-module (:util) members: $l") }

            // Transitive dependency (:core via :util's api export): complete members of a Greeter.
            labelsAt(
                ide, probe,
                "package com.example.app; import com.example.core.Greeter; class Probe { void m(){ Greeter g = new Greeter(); g.|CARET| } }",
            ).let { l -> assertTrue("greet" in l, "transitive (:core) member: $l") }

            // Platform (JDK via the workspace SDK): complete members of a String.
            labelsAt(
                ide, probe,
                "package com.example.app; class Probe { void m(){ String s = \"\"; s.|CARET| } }",
            ).let { l -> assertTrue(l.containsAll(listOf("length", "substring")), "platform (JDK) members: $l") }
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun completesInsideAnOnDiskFileBeingEdited() {
        val dir = Files.createTempDirectory("ide-demo")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val app = ide.modules().first { it.name == "app" }
            val mainFile = ide.sourceRoots(app).first().resolve("com/example/app/Main.java") // exists on disk
            val original = Files.readString(mainFile)
            // Inject a completion point into the real file's buffer (as the editor would).
            val text = original.replace(
                "Formatter formatter = new Formatter();",
                "Formatter formatter = new Formatter();\n        formatter.\n",
            )
            val offset = text.indexOf("formatter.\n") + "formatter.".length
            // bare names — a method's insertText now carries `()` (see CompletionInsertionTest)
            val names = runBlocking { ide.complete(mainFile, text, offset) }.items.map { it.insertText.substringBefore('(') }
            assertTrue(names.containsAll(listOf("format", "banner")), "completion inside the open on-disk file: $names")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun editingADependencyInTheEditorIsReflectedInDependents() {
        val dir = Files.createTempDirectory("ide-demo")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val util = ide.modules().first { it.name == "util" }
            val stringUtils = ide.sourceRoots(core).first().resolve("com/example/core/StringUtils.java")

            // Simulate editing StringUtils in its editor (overlay), WITHOUT an explicit save.
            val edited = Files.readString(stringUtils).trimEnd().dropLast(1) + " public static String brandNew() { return \"\"; } }"
            ide.updateDocument(stringUtils, edited)

            // Completing StringUtils. from :util must now see the just-introduced static method.
            val probe = ide.sourceRoots(util).first().resolve("com/example/util/Probe.java")
            val labels = labelsAt(
                ide, probe,
                "package com.example.util; import com.example.core.StringUtils; class Probe { void m(){ StringUtils.|CARET| } }",
            )
            assertTrue("brandNew" in labels, "unsaved edit to a dependency should be visible to dependents: $labels")
        }
        dir.toFile().deleteRecursively()
    }

    private fun labelsAt(ide: IdeServices, file: java.nio.file.Path, codeWithCaret: String): List<String> {
        val offset = codeWithCaret.indexOf("|CARET|")
        val text = codeWithCaret.replace("|CARET|", "")
        return runBlocking { ide.complete(file, text, offset) }.items.map { it.insertText.substringBefore('(') }
    }
}
