package dev.ide.android.support.tasks

import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Java compile output holds exactly the classes of the current sources, so nothing stale is dexed. */
class AndroidCompileOutputTest {

    @Test
    fun aDeletedSourceLeavesNoClassBehind() {
        withTempDir("compile-out") { dir ->
            val src = dir.resolve("src")
            val pkg = src.resolve("com/example")
            Files.createDirectories(pkg)
            Files.writeString(pkg.resolve("Keep.java"), "package com.example; public class Keep {}")
            Files.writeString(pkg.resolve("Gone.java"), "package com.example; public class Gone { class Inner {} }")
            val out = dir.resolve("classes")
            val task = AndroidCompileTask(
                name = TaskName(":app:compileJava"),
                sourceRoots = listOf(src),
                genJavaDir = dir.resolve("gen"),
                classpath = emptyList(),
                outClasses = out,
                level = "17",
                bootClasspath = emptyList(),
            )

            runBlocking { assertEquals(TaskResult.Success, task.execute(SimpleTaskContext())) }
            assertTrue(Files.isRegularFile(out.resolve("com/example/Gone.class")))
            assertTrue(Files.isRegularFile(out.resolve("com/example/Gone\$Inner.class")))

            Files.delete(pkg.resolve("Gone.java"))
            runBlocking { assertEquals(TaskResult.Success, task.execute(SimpleTaskContext())) }
            assertTrue(Files.isRegularFile(out.resolve("com/example/Keep.class")))
            assertFalse(Files.exists(out.resolve("com/example/Gone.class")), "class of a deleted source")
            assertFalse(Files.exists(out.resolve("com/example/Gone\$Inner.class")), "nested class of a deleted source")

            Files.delete(pkg.resolve("Keep.java"))
            runBlocking { assertEquals(TaskResult.Success, task.execute(SimpleTaskContext())) }
            assertFalse(Files.exists(out.resolve("com/example/Keep.class")), "class left after the last source went")
        }
    }
}
