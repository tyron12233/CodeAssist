package dev.ide.core

import dev.ide.build.BuildContext
import dev.ide.build.RunAction
import dev.ide.build.TEST_PROVIDER_EP
import dev.ide.build.TestDeclaration
import dev.ide.build.TestProvider
import dev.ide.build.TestReport
import dev.ide.lang.FILE_TYPE_EP
import dev.ide.lang.FileTypeMapping
import dev.ide.lang.LanguageId
import dev.ide.model.Module
import dev.ide.model.Project
import dev.ide.platform.PluginId
import dev.ide.ui.backend.ConsoleChunk
import dev.ide.ui.backend.ConsoleChunkKind
import dev.ide.ui.backend.TestOutput
import dev.ide.ui.backend.UiTestStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A plugin's [TestProvider]: run marks for its language, a Run-picker row, and results the Tests tab reads. */
class TestProviderTest {

    private val root = createTempDirectory("test-provider")
    private var services: IdeServices? = null

    @AfterTest
    fun tearDown() {
        services?.close()
        root.toFile().deleteRecursively()
    }

    /** `test NAME` lines are tests; the whole file is a group. */
    private object ToyTests : TestProvider {
        override val id = "toytest"
        override val displayName = "Toy tests"
        override val languages = setOf(LanguageId("toy"))
        override fun appliesTo(module: Module) = true
        override fun tests(module: Module, path: String, text: String): List<TestDeclaration> =
            listOf(TestDeclaration(path.substringAfterLast('/'), 0, path, group = true)) +
                Regex("(?m)^test (\\w+)").findAll(text).map { TestDeclaration(it.groupValues[1], it.range.first, it.groupValues[1]) }
        override fun run(module: Module, targets: List<String>, project: Project, ctx: BuildContext): RunAction? = null
    }

    @Test
    fun aProviderMarksItsTestsAndOffersARunRow() {
        val env = ApplicationEnvironment()
        val plugin = PluginId("test-toytest")
        env.platform.extensions.register(FILE_TYPE_EP, FileTypeMapping(listOf(".toy"), LanguageId("toy")), plugin)
        env.platform.extensions.register(TEST_PROVIDER_EP, ToyTests, plugin)
        val s = IdeServices.bootstrapJavaDemo(root, env).also { services = it }

        val text = "test adds\nsomething\ntest subtracts\n"
        val file = root.resolve("app/src/main/java/com/example/app/math.toy")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        s.modules()

        val marks = runBlocking { s.editorDecorations(file, text) }.gutter.filter { it.actionId?.startsWith(TestOutput.GUTTER_ACTION_PREFIX) == true }
        assertEquals(listOf(0, 0, 2), marks.map { it.line })
        assertEquals(
            "${TestOutput.GUTTER_ACTION_PREFIX}toytest:app:subtracts",
            marks.last().actionId,
        )
        assertEquals("Run subtracts", marks.last().tooltip)
        assertTrue(s.build.runTasks().any { it.id == "toytest:app" && it.label == "Toy tests · app" }, s.build.runTasks().map { it.id }.toString())
    }

    /** The two spellings of the record format (the published writer, the Tests tab's reader) agree. */
    @Test
    fun reportRecordsAreWhatTheTestsTabReads() {
        val out = TestReport.run("toytest") + "hello from the run\n" +
            TestReport.queued("math.toy", "adds") +
            TestReport.started("math.toy", "adds") + TestReport.passed("math.toy", "adds", 12) +
            TestReport.failed("math.toy", "subtracts", 3, "expected 1:2 but was\n3") +
            TestReport.skipped("math.toy", "later", "not yet") +
            TestReport.suiteFailed("broken.toy", "does not parse") +
            TestReport.finished()
        // Cut mid-record, as a console delivers it.
        val view = TestOutput.split(listOf(ConsoleChunk(out.take(40), ConsoleChunkKind.OUTPUT), ConsoleChunk(out.drop(40), ConsoleChunkKind.OUTPUT)))
        val run = view.tests!!
        assertEquals("toytest", run.kind)
        assertTrue(run.finished)
        assertEquals(listOf(UiTestStatus.Passed, UiTestStatus.Failed, UiTestStatus.Skipped), run.cases.map { it.status })
        assertEquals("expected 1:2 but was\n3", run.cases[1].message)
        assertEquals(12, run.cases[0].durationMs)
        assertEquals(mapOf("broken.toy" to "does not parse"), run.classFailures)
        assertEquals("hello from the run\n", view.transcript.joinToString("") { it.text })
        // A re-run comes back to the provider.
        // A re-run comes back to the provider, naming the test as `suite#test`.
        assertEquals(
            "toytest:app:math.toy#subtracts",
            TestOutput.rerunId("app", run.copy(cases = run.cases.filter { it.name == "subtracts" })),
        )
    }
}
