package dev.codeassist.ndk

import dev.ide.build.Task
import dev.ide.build.TaskInputs
import dev.ide.build.TaskInputsImpl
import dev.ide.build.TaskName
import dev.ide.build.TaskOutputs
import dev.ide.build.TaskOutputsImpl
import dev.ide.build.TaskResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/** The pieces of Run that need no device: finding `main`, decoding the output, staging the command. */
class NdkRunTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun findsMainInItsUsualShapes() {
        assertTrue(NativeMain.definesMain("int main() { return 0; }"))
        assertTrue(NativeMain.definesMain("#include <stdio.h>\n\nint main(int argc, char **argv) {\n}"))
        assertTrue(NativeMain.definesMain("  int  main (void)\n{ }"))
        assertTrue(NativeMain.definesMain("auto main() -> int { return 0; }"))
    }

    @Test
    fun aMainThatIsNotOneIsNotFound() {
        assertFalse(NativeMain.definesMain("// int main() { }\nint helper();"))
        assertFalse(NativeMain.definesMain("/*\nint main() {}\n*/\nvoid f();"))
        assertFalse(NativeMain.definesMain("int domain(int x) { return x; }"))
        assertFalse(NativeMain.definesMain("void call() { main_loop(); }"))
        assertFalse(NativeMain.definesMain("extern \"C\" JNIEXPORT jstring JNICALL Java_a_B_c(JNIEnv*, jobject) {}"))
    }

    @Test
    fun theAnswerFollowsTheFile() {
        val file = dir.resolve("main.c")
        file.writeText("void f(void) {}\n")
        assertFalse(NativeMain.definesMain(file))
        file.writeText("int main(void) { return 0; }\n")
        // Same second on a coarse clock: move the timestamp so the cache sees a different file.
        file.toFile().setLastModified(file.toFile().lastModified() + 2_000)
        assertTrue(NativeMain.definesMain(file))
    }

    @Test
    fun utf8SplitAcrossChunksIsReassembled() {
        val bytes = "héllo €!".toByteArray()
        val decoder = Utf8Chunks()
        val out = StringBuilder()
        // One byte at a time: every multi-byte character is split.
        for (b in bytes) out.append(decoder.decode(byteArrayOf(b), 1))
        out.append(decoder.finish())
        assertEquals("héllo €!", out.toString())
    }

    @Test
    fun outputIsSplitIntoLinesForTheBuildLog() {
        val lines = ArrayList<String>()
        val splitter = LineSplitter { lines += it }
        splitter.append("Name: ")
        splitter.append("tyron\r\nnext ")
        splitter.append("line\npartial")
        splitter.flush()
        assertEquals(listOf("Name: tyron", "next line", "partial"), lines)
    }

    @Test
    fun linkThenRun() {
        val link = FakeTask("link")
        val run = FakeTask("run")
        val graph = TwoStepGraph(link, run)
        assertEquals(listOf(link, run), graph.tasks)
        assertEquals(listOf(link), graph.dependencies(run))
        assertEquals(emptyList<Task>(), graph.dependencies(link))
        assertEquals(listOf(listOf(link), listOf(run)), graph.topologicalLevels())
    }

    @Test
    fun theCommandRunsTheProgramThroughTheLauncher() {
        val program = dir.resolve("bin").resolve("app").also { it.parent.toFile().mkdirs(); it.writeText("") }
        val launcher = dir.resolve("libndkrun.so")
        val launch = NativeLaunch.prepare(program, "app", launcher)
        val onAndroid = java.io.File("/system/bin/linker64").isFile
        if (onAndroid) {
            // Copied into internal storage and handed to the system linker: an app cannot exec its own files.
            val staged = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "ndk-run", "app", "app")
            assertEquals(listOf(launcher.toString(), "/system/bin/linker64", staged.toString()), launch.command)
            assertTrue(java.nio.file.Files.isRegularFile(staged))
        } else {
            assertEquals(listOf(launcher.toString(), program.toString()), launch.command)
        }
        assertEquals(java.nio.file.Paths.get(launch.command.last()).parent.toString(), launch.environment["LD_LIBRARY_PATH"])

        // No launcher (a toolchain from before it existed): the program itself, over plain pipes.
        assertEquals(launch.command.drop(1), NativeLaunch.prepare(program, "app", null).command)
    }

    private class FakeTask(id: String) : Task {
        override val name = TaskName(":m:$id")
        override val inputs: TaskInputs get() = TaskInputsImpl()
        override val outputs: TaskOutputs get() = TaskOutputsImpl()
        override suspend fun execute(ctx: dev.ide.build.TaskContext) = TaskResult.Success
    }
}
