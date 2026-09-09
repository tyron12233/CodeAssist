package dev.ide.core

import dev.ide.interp.api.CODE_INTERPRETER
import dev.ide.interp.api.CodeInterpreter
import dev.ide.interp.api.LoweredProgram
import dev.ide.interp.api.LowerRequest
import dev.ide.interp.api.LowerResult
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plugin-facing interpreter, end to end through the engine: an installed plugin resolves
 * `CODE_INTERPRETER` from the application container, lowers a declaration out of a real project, and runs it.
 *
 * The pieces this covers that the session tests in `:interp-impl` cannot: the service is actually registered,
 * it follows the open project, lowering resolves against that project's own classpath, and the three
 * `LowerResult` cases are distinguishable by a caller who has to decide whether to retry.
 */
class CodeInterpreterTest {

    private val scene = """
        class Scene(val name: String) {
            var frames = 0
            fun render(): String {
                frames++
                return name + " frame " + frames
            }
        }

        fun sceneCount(): Int = 3
    """.trimIndent()

    /** A Kotlin scratch project with [code] in it, plus the manager whose container serves the interpreter. */
    private fun <T> withKotlinProject(code: String, body: (ProjectManager, Path) -> T): T =
        withTempDir("code-interpreter") { home ->
            val projects = home.resolve("projects")
            Files.createDirectories(projects)
            val manager = ProjectManager.desktop(projects)
            try {
                val services = manager.scratch(
                    "kotlin-lib", "java-library",
                    mapOf("packageName" to "dev.ide.test", "language" to "kotlin"),
                )
                val root = services.sourceRoots(services.modules().first()).first()
                val file = root.resolve("Scene.kt")
                Files.createDirectories(file.parent)
                Files.write(file, code.toByteArray())
                runBlocking { services.indexStatus.first { !it.building } }
                body(manager, file)
            } finally {
                manager.dispose()
            }
        }

    @Test
    fun `a plugin lowers a class out of the open project and drives it`() {
        withKotlinProject(scene) { manager, file ->
            val interp = manager.env.container.getService(CODE_INTERPRETER)
            val program = interp.awaitLowered(LowerRequest(file = file, entry = "Scene", text = scene))
            assertEquals("Scene", program.entry)
            assertTrue("Scene" in program.types, program.types.toString())

            val session = interp.openSource(program)
            try {
                val instance = session.instantiate("Scene", listOf("title"))
                assertEquals("title frame 1", instance.call("render"))
                assertEquals("title frame 2", instance.call("render"))
                assertEquals(2, instance.get("frames"))
            } finally {
                session.dispose()
            }
        }
    }

    @Test
    fun `a top-level function entry is lowered and called`() {
        withKotlinProject(scene) { manager, file ->
            val interp = manager.env.container.getService(CODE_INTERPRETER)
            val program = interp.awaitLowered(LowerRequest(file = file, entry = "sceneCount", text = scene))
            assertEquals("sceneCount/0", program.entry)
            val session = interp.openSource(program)
            try {
                assertEquals(3, session.call(program.entry))
            } finally {
                session.dispose()
            }
        }
    }

    @Test
    fun `an unknown entry fails with what the file declares`() {
        withKotlinProject(scene) { manager, file ->
            val interp = manager.env.container.getService(CODE_INTERPRETER)
            val result = interp.awaitSettled(LowerRequest(file = file, entry = "Nope", text = scene))
            val failed = result as? LowerResult.Failed ?: error("expected a failure, got ${result.describe()}")
            assertTrue(failed.problems.any { "Scene" in it }, failed.problems.toString())
        }
    }

    @Test
    fun `a file with syntax errors fails rather than running a garbage program`() {
        withKotlinProject(scene) { manager, file ->
            val interp = manager.env.container.getService(CODE_INTERPRETER)
            val broken = "class Scene( {"
            val result = interp.awaitSettled(LowerRequest(file = file, entry = "Scene", text = broken))
            val failed = result as? LowerResult.Failed ?: error("expected a failure, got ${result.describe()}")
            assertTrue(failed.problems.any { "syntax" in it }, failed.problems.toString())
        }
    }

    @Test
    fun `the live buffer is what gets lowered, not the file on disk`() {
        withKotlinProject(scene) { manager, file ->
            val interp = manager.env.container.getService(CODE_INTERPRETER)
            val edited = scene.replace("\" frame \"", "\" edited \"")
            val program = interp.awaitLowered(LowerRequest(file = file, entry = "Scene", text = edited))
            val session = interp.openSource(program)
            try {
                assertEquals("t edited 1", session.instantiate("Scene", listOf("t")).call("render"))
            } finally {
                session.dispose()
            }
        }
    }

    @Test
    fun `a file outside any open project is not ready rather than failed`() {
        withKotlinProject(scene) { manager, _ ->
            val interp = manager.env.container.getService(CODE_INTERPRETER)
            val outside = Files.createTempFile("outside", ".kt")
            Files.write(outside, "fun a() = 1\n".toByteArray())
            try {
                val result = interp.lower(LowerRequest(file = outside, entry = "a"))
                // Not a failure: a plugin should retry, because "no module owns this" is what an editor tab for
                // a not-yet-imported file looks like. Answered before any index gate, so this is deterministic.
                val notReady = result as? LowerResult.NotReady ?: error("got ${result.describe()}")
                assertTrue("owns" in notReady.message, notReady.message)
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }
}

/** Failure messages that say WHY, not just which subclass: a `NotReady` with no reason is unreadable. */
private fun LowerResult.describe(): String = when (this) {
    is LowerResult.Lowered -> "Lowered(${program.entry})"
    is LowerResult.NotReady -> "NotReady(${message})"
    is LowerResult.Failed -> "Failed(${problems})"
}

/**
 * Lower [request], retrying while the answer is `NotReady`.
 *
 * This is the contract in use, not a workaround: a module's Kotlin classpath index builds in the background,
 * so the first lower after a project opens legitimately answers "not yet", and a plugin is expected to come
 * back rather than report a failure. Bounded, so a genuine wedge fails the test instead of hanging it.
 */
private fun CodeInterpreter.awaitLowered(request: LowerRequest): LoweredProgram =
    when (val settled = awaitSettled(request)) {
        is LowerResult.Lowered -> settled.program
        else -> error("expected a lowered program, got ${settled.describe()}")
    }

/** [awaitLowered] without requiring success: the first answer that is not `NotReady`. */
private fun CodeInterpreter.awaitSettled(request: LowerRequest): LowerResult {
    var last: LowerResult = LowerResult.NotReady("never asked")
    repeat(300) {
        last = lower(request)
        if (last !is LowerResult.NotReady) return last
        Thread.sleep(200)
    }
    error("lowering never settled: ${last.describe()}")
}
