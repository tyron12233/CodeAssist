package dev.ide.core

import dev.ide.vfs.FileChanged
import dev.ide.vfs.FileDeleted
import dev.ide.vfs.VfsEvent
import dev.ide.vfs.VfsListener
import dev.ide.vfs.VfsTopics
import dev.ide.testkit.withTempDir
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
        withTempDir("events-save") { dir ->
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
        }
    }

    @Test
    fun deletePublishesFileDeletedOnTheBus() {
        withTempDir("events-del") { dir ->
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
        }
    }

    // ---- writes that bypass the editor ---------------------------------------------------------------
    //
    // The overlay wins over disk wherever it is consulted, and it outlives the tab that created it, so a
    // writer that goes straight to disk (a plugin's ModuleResources write, a template, an external tool)
    // used to land its bytes on disk and NOWHERE else for the rest of the session.

    @Test
    fun aWriteBehindAnOpenBufferRefreshesTheOverlay() {
        withTempDir("events-overlay") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val file = ide.sourceRoots(core).first().resolve("com/example/core/Scratch.java")
            Files.writeString(file, "package com.example.core; class Scratch { int before; }")
            ide.updateDocument(file, Files.readString(file)) // the editor opens a tab on it

            // A writer that does not go through save(): bytes to disk, then publish.
            Files.writeString(file, "package com.example.core; class Scratch { int after; }")
            ide.events.fileChanged(file)

            assertTrue(
                "int after" in ide.readCurrentText(file),
                "the overlay still hid the write: ${ide.readCurrentText(file)}",
            )
        }
        }
    }

    @Test
    fun onlyAWriteTheEditorDidNotDriveNotifiesFileSystemListeners() {
        withTempDir("events-fs") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val file = ide.sourceRoots(core).first().resolve("com/example/core/Scratch.java")
            var changes = 0
            val sub = ide.addFileSystemListener { changes++ }

            ide.save(file, "package com.example.core; class Scratch {}")
            assertEquals(0, changes, "a save carries its text; the UI already has it and must not re-walk the tree")

            Files.writeString(file, "package com.example.core; class Scratch { int x; }")
            ide.events.fileChanged(file)
            assertEquals(1, changes, "a write the UI did not drive has to reach the tree and the open tabs")
            sub.dispose()

            ide.events.fileChanged(file)
            assertEquals(1, changes, "disposed listener stays disposed")
        }
        }
    }

    @Test
    fun closingATabDropsItsOverlaySoAnalysisReadsDiskAgain() {
        withTempDir("events-close") { dir ->
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val core = ide.modules().first { it.name == "core" }
            val file = ide.sourceRoots(core).first().resolve("com/example/core/Scratch.java")
            val onDisk = "package com.example.core; class Scratch {}"
            Files.writeString(file, onDisk)

            ide.updateDocument(file, "package com.example.core; class Scratch { int unsaved; }")
            assertTrue("unsaved" in ide.readCurrentText(file), "the live buffer wins while the tab is open")

            ide.documentClosed(file)
            assertEquals(
                onDisk, ide.readCurrentText(file),
                "an overlay that outlives its tab hides every later write to that file",
            )
        }
        }
    }

    @Test
    fun configurationChangesBumpTheStampAndNotifyListeners() {
        withTempDir("events-config") { dir ->
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
        }
    }
}
