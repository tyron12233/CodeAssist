package dev.ide.core

import dev.ide.vfs.FileChanged
import dev.ide.vfs.FileDeleted
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VfsListener
import dev.ide.vfs.VfsTopics
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The workspace event spine ([WorkspaceEventHub]): engine mutations PUBLISH typed events on the message
 * bus, and the invalidation chains run as subscribers, so any consumer (and the out-of-process hint
 * fan-out) can observe the same stream the engine itself reacts to.
 *
 * Test bodies are block-bodied (not `= runBlocking { … }`): an expression body would take the return type
 * of its last statement, and a non-Unit return makes JUnit silently skip the method.
 */
class WorkspaceEventsTest {

    @Test
    fun savePublishesFileChangedOnTheBus() {
        val dir = Files.createTempDirectory("events-save")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val seen = ArrayList<VfsEvent>()
            val conn = ide.platform.messageBus.connect()
            conn.subscribe(VfsTopics.CHANGES, object : VfsListener {
                override fun onEvents(events: List<VfsEvent>) { seen.addAll(events) }
            })
            val core = ide.modules().first { it.name == "core" }
            val file = ide.sourceRoots(core).first().resolve("com/example/core/Scratch.java")
            ide.save(file, "package com.example.core; class Scratch {}")
            conn.dispose()

            val changed = seen.filterIsInstance<FileChanged>()
            assertEquals(1, changed.size, "one FileChanged per save, got: $seen")
            assertEquals(file.toString(), changed.single().file.path)
            assertTrue(changed.single().newHash.value.isNotEmpty(), "save carries the new content hash")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun deletePublishesFileDeletedOnTheBus() {
        val dir = Files.createTempDirectory("events-del")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val file = ide.sourceRoots(core).first().resolve("com/example/core/Scratch.java")
            Files.writeString(file, "package com.example.core; class Scratch {}")

            val seen = ArrayList<VfsEvent>()
            val conn = ide.platform.messageBus.connect()
            conn.subscribe(VfsTopics.CHANGES, object : VfsListener {
                override fun onEvents(events: List<VfsEvent>) { seen.addAll(events) }
            })
            assertTrue(ide.deletePath(file))
            conn.dispose()

            val deleted = seen.filterIsInstance<FileDeleted>()
            assertEquals(1, deleted.size, "one FileDeleted per delete, got: $seen")
            assertEquals(file.toString(), deleted.single().file.path)
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun configurationChangesBumpTheStampAndNotifyListeners() {
        val dir = Files.createTempDirectory("events-config")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val before = ide.events.configStamp.get()
            var notified = 0
            val sub = ide.addConfigurationListener { notified++ }

            // A variant switch is a config change even though the model generation does not advance.
            val core = ide.modules().first { it.name == "core" }
            ide.setActiveVariant(core, "customVariant")

            assertTrue(ide.events.configStamp.get() > before, "variant change bumps the config stamp")
            assertTrue(notified >= 1, "configuration listener fired")
            sub.dispose()

            // A model commit (dependency change) also bumps it, via the ProjectModelTopics subscription.
            // The demo's edges are util→core and app→util, so app→core is new and cycle-free.
            val stampBeforeCommit = ide.events.configStamp.get()
            val added = ide.dependencies.addModuleDependency("app", "core", "implementation")
            assertTrue(added.success, "module dep add failed: ${added.message}")
            assertTrue(
                ide.events.configStamp.get() > stampBeforeCommit,
                "a model commit bumps the config stamp",
            )
        }
        dir.toFile().deleteRecursively()
    }
}
