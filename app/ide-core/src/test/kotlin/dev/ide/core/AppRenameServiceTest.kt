package dev.ide.core

import dev.ide.android.support.AndroidFacet
import dev.ide.core.project.ProjectManager
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.UiAppRenameRequest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppRenameServiceTest {

    private fun sources(dir: Path): List<Path> = Files.walk(dir).use { s ->
        s.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
            .collect(Collectors.toList())
    }

    private fun facet(ide: IdeServices): AndroidFacet = ide.modules().first { it.name == "app" }.facets.get(AndroidFacet.KEY)!!

    /** The package moves (directories, package lines, namespace) while the app keeps its id. */
    @Test
    fun renamesThePackageAndKeepsTheAppId() {
        withTempDir("app-rename") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            val rootPath = manager.create("android-app", mapOf("name" to "Shop", "packageName" to "com.acme.shop")).use { ide ->
                val identity = assertNotNull(ide.appRename.identity("app"))
                assertEquals("com.acme.shop", identity.packageName)
                assertEquals("Shop", identity.appName)
                assertEquals("com.acme.shop", identity.applicationId)

                val request = UiAppRenameRequest("app", newPackage = "org.example.store", newAppName = null, changeApplicationId = false)
                val preview = ide.appRename.preview(request)
                assertNull(preview.error)
                assertTrue(preview.moved > 0, "the sources move: $preview")
                assertTrue(preview.notes.any { "keeps the id com.acme.shop" in it }, "${preview.notes}")

                val result = ide.appRename.apply(request)
                assertTrue(result.success, result.message)
                assertTrue(result.movedPaths.isNotEmpty())

                val app = ide.workspaceRoot.resolve("app")
                val javaRoot = app.resolve("src/main/java")
                assertFalse(Files.exists(javaRoot.resolve("com")), "the old package directories are gone")
                val moved = sources(javaRoot.resolve("org/example/store"))
                assertTrue(moved.isNotEmpty())
                moved.forEach { assertTrue("package org.example.store" in it.readText(), "$it: ${it.readText()}") }
                assertFalse(sources(app).any { "com.acme.shop" in it.readText() })

                assertEquals("org.example.store", facet(ide).namespace)
                assertEquals("com.acme.shop", facet(ide).applicationId)
                ide.workspaceRoot.toString()
            }
            // Persisted, not just in memory.
            manager.open(rootPath).use { reopened ->
                assertEquals("org.example.store", facet(reopened).namespace)
                assertEquals("com.acme.shop", facet(reopened).applicationId)
            }
        }
    }

    @Test
    fun renamesTheAppNameAndMovesTheAppIdWithThePackage() {
        withTempDir("app-rename-name") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("android-app", mapOf("name" to "Shop", "packageName" to "com.acme.shop")).use { ide ->
                val result = ide.appRename.apply(
                    UiAppRenameRequest("app", newPackage = "com.acme.store", newAppName = "Bob's Shop & Co", changeApplicationId = true),
                )
                assertTrue(result.success, result.message)
                val identity = assertNotNull(ide.appRename.identity("app"))
                assertEquals("Bob's Shop & Co", identity.appName)
                assertEquals("com.acme.store", identity.packageName)
                assertEquals("com.acme.store", identity.applicationId)
                assertNull(facet(ide).applicationId, "an id equal to the namespace isn't pinned")
                val strings = ide.workspaceRoot.resolve("app/src/main/res/values/strings.xml").readText()
                assertTrue("""<string name="app_name">Bob\'s Shop &amp; Co</string>""" in strings, strings)
            }
        }
    }

    @Test
    fun refusesAnInvalidPackageWithoutTouchingAnything() {
        withTempDir("app-rename-invalid") { root ->
            val manager = ProjectManager.desktop(root.resolve("projects"))
            manager.create("android-app", mapOf("name" to "Shop", "packageName" to "com.acme.shop")).use { ide ->
                val request = UiAppRenameRequest("app", newPackage = "com.class.app", newAppName = null)
                assertNotNull(ide.appRename.preview(request).error)
                assertFalse(ide.appRename.apply(request).success)
                assertEquals("com.acme.shop", facet(ide).namespace)
                assertTrue(Files.isDirectory(ide.workspaceRoot.resolve("app/src/main/java/com/acme/shop")))
            }
        }
    }
}
