package dev.ide.core

import dev.ide.ui.backend.UiOpenTab
import dev.ide.ui.backend.UiOpenTabs
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor remembers the session per project: [IdeServicesBackend.saveOpenTabs] writes the open tabs — each
 * with its caret, scroll line, and view mode — under the project's `.platform/`, and [IdeServicesBackend.openTabs]
 * reads them back on the next launch. SDK-independent — pure file I/O over a bootstrapped Java demo.
 */
class OpenTabsPersistenceTest {

    @Test
    fun roundTripsOpenTabsWithPerTabState() {
        val dir = Files.createTempDirectory("ide-tabs")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)

            // Nothing saved yet → an empty session.
            assertEquals(UiOpenTabs(), backend.projects.openTabs())

            val tabs = UiOpenTabs(
                listOf(
                    UiOpenTab("/a/Foo.java", caret = 42, scrollLine = 3, viewMode = "text"),
                    UiOpenTab("/a/Bar.java", caret = 0, scrollLine = 0, viewMode = "blocks"),
                    UiOpenTab("/b/layout.xml", caret = 128, scrollLine = 7, viewMode = "split"),
                ),
                activeIndex = 2,
            )
            backend.projects.saveOpenTabs(tabs)

            // Persisted alongside the rest of the workspace state, not in the (disposable) caches dir.
            assertTrue(Files.exists(ide.workspaceRoot.resolve(".platform/open-tabs.txt")), "tabs file written")

            // Caret, scroll line, view mode, and active index all round-trip.
            assertEquals(tabs, backend.projects.openTabs(), "full per-tab session round-trips")
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

    @Test
    fun readsLegacyV1Format() {
        val dir = Files.createTempDirectory("ide-tabs-v1")
        IdeServices.bootstrapJavaDemo(dir).use { ide ->
            val backend = IdeServicesBackend(ide)
            // An old (pre-caret/scroll) session file: active index, then bare paths.
            val file = ide.workspaceRoot.resolve(".platform/open-tabs.txt")
            Files.createDirectories(file.parent)
            Files.writeString(file, "1\n/a/Foo.java\n/a/Bar.java\n")

            val restored = backend.projects.openTabs()
            assertEquals(listOf("/a/Foo.java", "/a/Bar.java"), restored.paths, "legacy paths still read")
            assertEquals(1, restored.activeIndex)
            // Legacy tabs default to top-of-file / plain text.
            assertEquals(UiOpenTab("/a/Foo.java"), restored.tabs.first())
        }
        dir.toFile().deleteRecursively()
    }
}
