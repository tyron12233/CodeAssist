package dev.ide.ui.components

import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeNode
import kotlin.test.Test
import kotlin.test.assertEquals

/** The file tree drops `.java`/`.kt` beside the J/K badge, and nothing else. */
class TreeDisplayNameTest {

    private fun file(name: String, icon: String) = TreeNode("file:$name", name, NodeKind.File, "/p/$name", icon)

    @Test
    fun sourceFilesDropTheExtensionTheBadgeShows() {
        assertEquals("AnalysisManager", file("AnalysisManager.java", "java").displayName())
        assertEquals("Main", file("Main.kt", "kotlin").displayName())
    }

    @Test
    fun everythingElseKeepsItsName() {
        assertEquals("build.gradle.kts", file("build.gradle.kts", "kotlin").displayName())
        assertEquals("notes.java.txt", file("notes.java.txt", "file").displayName())
        assertEquals("README.md", file("README.md", "markdown").displayName())
        // A file literally named `.kt` keeps it rather than showing an empty row.
        assertEquals(".kt", file(".kt", "kotlin").displayName())
        val folder = TreeNode("dir:/p/x.java", "x.java", NodeKind.Folder, null, "java")
        assertEquals("x.java", folder.displayName())
    }
}
