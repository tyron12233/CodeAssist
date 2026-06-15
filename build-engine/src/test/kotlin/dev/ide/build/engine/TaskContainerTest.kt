package dev.ide.build.engine

import dev.ide.build.CyclicTaskDependencyException
import dev.ide.build.Task
import dev.ide.build.TaskContext
import dev.ide.build.TaskGraph
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Gradle-style configuration phase: lazy [DefaultTaskContainer.register], reference-by-name before
 *  registration, [DefaultTaskContainer.configureEach], and cycle detection at realize. */
class TaskContainerTest {

    private fun task(id: String) = object : Task {
        override val name = TaskName(id)
        override val inputs get() = TaskInputsImpl().apply { property("id", id) }
        override val outputs get() = TaskOutputsImpl()
        override suspend fun execute(ctx: TaskContext): TaskResult = TaskResult.Success
    }

    private fun level(g: TaskGraph, id: String) = g.topologicalLevels().indexOfFirst { lvl -> lvl.any { it.name.value == id } }

    @Test
    fun factoryIsLazyAndRunsOnlyAtBuild() {
        var created = false
        val c = DefaultTaskContainer()
        c.register(TaskName(":a")) { created = true; task(":a") }
        assertFalse(created, "the task factory must not run during configuration")
        c.build()
        assertTrue(created, "the factory runs when the container is realized")
    }

    @Test
    fun configureByNameBeforeRegistrationResolves() {
        val c = DefaultTaskContainer()
        c.named(TaskName(":b")).configure { dependsOn(":a") } // :a not registered yet (another plugin will)
        c.register(TaskName(":a")) { task(":a") }
        c.register(TaskName(":b")) { task(":b") }
        val g = c.build()
        val b = g.tasks.first { it.name.value == ":b" }
        assertEquals(listOf(":a"), g.dependencies(b).map { it.name.value }, "name edge to a later-registered task resolves")
        assertTrue(level(g, ":a") < level(g, ":b"))
    }

    @Test
    fun configureEachAppliesToEveryTask() {
        val c = DefaultTaskContainer()
        c.register(TaskName(":lib")) { task(":lib") }
        c.register(TaskName(":app")) { task(":app") }
        c.configureEach { if (name.value == ":app") dependsOn(":lib") }
        val g = c.build()
        val app = g.tasks.first { it.name.value == ":app" }
        assertEquals(listOf(":lib"), g.dependencies(app).map { it.name.value })
    }

    @Test
    fun mustRunAfterOrdersWithoutBlocking() {
        val c = DefaultTaskContainer()
        c.register(TaskName(":a")) { task(":a") }
        c.register(TaskName(":b")) { task(":b") }.configure { mustRunAfter(":a") }
        val g = c.build()
        assertTrue(level(g, ":a") < level(g, ":b"), "mustRunAfter sequences b after a")
        assertTrue(g.dependencies(g.tasks.first { it.name.value == ":b" }).isEmpty(), "ordering-only, not a hard dep")
    }

    @Test
    fun cyclesAreDetectedAtRealize() {
        val c = DefaultTaskContainer()
        c.register(TaskName(":a")) { task(":a") }.configure { dependsOn(":b") }
        c.register(TaskName(":b")) { task(":b") }.configure { dependsOn(":a") }
        val g = c.build()
        assertFailsWith<CyclicTaskDependencyException> { g.topologicalLevels() }
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        val c = DefaultTaskContainer()
        c.register(TaskName(":a")) { task(":a") }
        assertFailsWith<IllegalArgumentException> { c.register(TaskName(":a")) { task(":a") } }
    }
}
