package dev.ide.core

import dev.ide.ui.backend.UiOpenTabs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor remembers which tabs were open per project: [IdeServicesBackend.saveOpenTabs] writes the open
 * file paths + active index under the project's `.platform/`, and [IdeServicesBackend.openTabs] reads them
 * back on the next launch. SDK-independent — pure file I/O over a bootstrapped Java demo.
 */
class OpenTabsPersistenceTest {

    @Test
    fun roundTripsOpenTabs() {
        val dir = Files.createTempDirectory("ide-tabs")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)

            // Nothing saved yet → an empty session.
            assertEquals(UiOpenTabs(), backend.projects.openTabs())

            val tabs = UiOpenTabs(listOf("/a/Foo.java", "/a/Bar.java", "/b/Baz.kt"), activeIndex = 2)
            backend.projects.saveOpenTabs(tabs)

            // Persisted alongside the rest of the workspace state, not in the (disposable) caches dir.
            assertTrue(Files.exists(ide.workspaceRoot.resolve(".platform/open-tabs.txt")), "tabs file written")

            assertEquals(tabs, backend.projects.openTabs(), "paths and active index round-trip")
        }
        dir.toFile().deleteRecursively()
    }

    @Test
    fun toleratesAMissingOrEmptyActiveIndex() {
        val dir = Files.createTempDirectory("ide-tabs-empty")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            backend.projects.saveOpenTabs(UiOpenTabs(emptyList(), activeIndex = -1))
            val restored = backend.projects.openTabs()
            assertEquals(emptyList(), restored.paths)
            assertEquals(-1, restored.activeIndex)
        }
        dir.toFile().deleteRecursively()
    }
}
