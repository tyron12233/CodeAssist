@file:OptIn(ExperimentalForeignApi::class)

package dev.ide.ios

import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.TreeViewMode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [IosBackend] against the real filesystem, on the simulator.
 *
 * The backend is pointed at a temporary directory rather than the Documents container, so a test run can
 * never disturb real projects.
 */
class IosBackendTest {

    private val root = IosFiles.join(NSTemporaryDirectory().trimEnd('/'), "ios-backend-test-${nowSuffix()}")
    private val backend = IosBackend(root)

    @AfterTest
    fun cleanUp() {
        IosFiles.delete(root)
    }

    // ---- Kotlin outline and folding, which is the first language intelligence this host has ever had ----

    private val kotlinSource = """
        package demo

        import kotlin.math.max

        class Holder {
            fun render(prefix: String): String {
                return prefix
            }
        }
    """.trimIndent()

    @Test
    fun aKotlinFileHasAnOutline() = runTest {
        val outline = backend.fileStructure("/p/Holder.kt", kotlinSource)
        assertEquals(listOf("Holder", "render"), outline.map { it.name })
        assertEquals(listOf("class", "method"), outline.map { it.kind })
        assertEquals(listOf(0, 1), outline.map { it.depth }, "the function is a member of the class")
        assertEquals(
            "render",
            kotlinSource.substring(outline[1].nameOffset, outline[1].nameOffset + 6),
            "the offset must land on the name, since that is where navigation puts the caret",
        )
    }

    @Test
    fun aKotlinFileHasFoldableRegions() = runTest {
        val kinds = backend.codeFolds("/p/Holder.kt", kotlinSource).map { it.kind }.toSet()
        assertTrue("classBody" in kinds, kinds.toString())
        assertTrue("functionBody" in kinds, kinds.toString())
    }

    @Test
    fun halfTypedKotlinStillAnswers() = runTest {
        // What an editor actually holds most of the time. It must not throw and must not go blank.
        val outline = backend.fileStructure("/p/Broken.kt", "class A {\n    fun good() {}\n    fun \n}")
        assertTrue(outline.any { it.name == "A" }, "the class survives: $outline")
        assertTrue(outline.any { it.name == "good" }, "and the complete member does too: $outline")
    }

    @Test
    fun aNonKotlinFileIsLeftAlone() = runTest {
        // The other hosts answer for Java and XML through backends that do not exist here. Returning nothing
        // is honest; guessing would put a Kotlin outline on a Java file.
        assertTrue(backend.fileStructure("/p/Thing.java", "class Thing {}").isEmpty())
        assertTrue(backend.codeFolds("/p/layout.xml", "<a>\n</a>").isEmpty())
    }

    @Test
    fun createProjectScaffoldsAFolderAndOpensIt() = runTest {
        val result = backend.projects.createProject("ios.empty", mapOf("name" to "My App", "packageName" to "com.example"))
        assertTrue(result.success, result.message)

        val created = assertNotNull(result.rootPath)
        // "My App" is not a safe folder name; the space is replaced rather than rejected.
        assertEquals("My_App", IosFiles.nameOf(created))
        assertTrue(IosFiles.isDirectory(IosFiles.join(created, "src")))

        val main = IosFiles.join(created, "src/Main.kt")
        assertTrue(IosFiles.exists(main))
        assertTrue(backend.files.readFile(main).startsWith("package com.example"))

        // Creating a project also makes it the active one, so the tree is immediately populated.
        assertEquals(created, backend.project.rootPath)
    }

    @Test
    fun aSecondProjectWithTheSameNameIsRefused() = runTest {
        assertTrue(backend.projects.createProject("ios.empty", mapOf("name" to "Dup")).success)
        val again = backend.projects.createProject("ios.empty", mapOf("name" to "Dup"))
        assertTrue(!again.success)
        assertTrue(again.message.contains("already exists"), again.message)
    }

    @Test
    fun anUnnamedProjectIsRefused() = runTest {
        val result = backend.projects.createProject("ios.empty", mapOf("name" to "   "))
        assertTrue(!result.success)
    }

    @Test
    fun projectsListsWhatIsOnDisk() = runTest {
        backend.projects.createProject("ios.empty", mapOf("name" to "Alpha"))
        backend.projects.createProject("ios.empty", mapOf("name" to "Beta"))
        assertEquals(setOf("Alpha", "Beta"), backend.projects.projects().map { it.name }.toSet())
    }

    @Test
    fun fileTreeShowsDirectoriesBeforeFilesAndHidesDotEntries() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "Tree")).rootPath)
        IosFiles.writeText(IosFiles.join(created, "README.md"), "hi")
        IosFiles.writeText(IosFiles.join(created, ".hidden"), "x")

        val tree = backend.files.fileTree(TreeViewMode.Project)
        assertEquals(NodeKind.Workspace, tree.kind)
        assertEquals(listOf("src", "README.md"), tree.children.map { it.name })
        assertEquals(NodeKind.Folder, tree.children[0].kind)
        assertEquals("markdown", tree.children[1].iconId)

        val main = tree.children[0].children.single()
        assertEquals("Main.kt", main.name)
        assertEquals("kotlin", main.iconId)
        assertEquals(IosFiles.join(created, "src/Main.kt"), main.filePath)
    }

    @Test
    fun withNoProjectOpenTheTreeIsEmptyRatherThanBroken() {
        val tree = IosBackend(root).files.fileTree(TreeViewMode.Project)
        assertEquals(NodeKind.Workspace, tree.kind)
        assertTrue(tree.children.isEmpty())
    }

    @Test
    fun saveFileWritesThroughToDisk() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "Save")).rootPath)
        val main = IosFiles.join(created, "src/Main.kt")
        backend.editor.saveFile(main, "fun main() = Unit\n")
        assertEquals("fun main() = Unit\n", backend.files.readFile(main))
    }

    @Test
    fun createAndDeleteBumpTheFilesystemEpoch() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "Epoch")).rootPath)
        val before = backend.files.fileSystemEpoch.value

        val made = assertNotNull(backend.files.createFile(created, "Extra.kt", "// x"))
        assertTrue(backend.files.fileSystemEpoch.value > before)

        assertTrue(backend.files.deletePath(made))
        assertTrue(!IosFiles.exists(made))
    }

    @Test
    fun createFileRefusesToOverwriteAnExistingOne() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "NoClobber")).rootPath)
        assertNotNull(backend.files.createFile(created, "A.kt", "first"))
        assertNull(backend.files.createFile(created, "A.kt", "second"))
        assertEquals("first", backend.files.readFile(IosFiles.join(created, "A.kt")))
    }

    @Test
    fun createFileSmartMakesIntermediateDirectories() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "Nested")).rootPath)
        val made = assertNotNull(backend.files.createFileSmart(created, "ui/screens/Home.kt"))
        assertTrue(IosFiles.exists(made))
        assertTrue(IosFiles.isDirectory(IosFiles.join(created, "ui/screens")))
    }

    @Test
    fun deletingTheOpenProjectClosesIt() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "Gone")).rootPath)
        assertEquals(created, backend.project.rootPath)
        assertTrue(backend.projects.deleteProject(created))
        assertEquals("", backend.project.rootPath)
    }

    @Test
    fun moduleNameIsTheProjectForPathsInsideItAndNullOutside() = runTest {
        val created = assertNotNull(backend.projects.createProject("ios.empty", mapOf("name" to "Mod")).rootPath)
        assertEquals("Mod", backend.files.moduleNameForFile(IosFiles.join(created, "src/Main.kt")))
        assertNull(backend.files.moduleNameForFile("/elsewhere/Other.kt"))
    }

    /**
     * Every picture the UI draws goes through [IosBackend.imageBytes]: a store listing's icon, a
     * screenshot gallery, a publisher's avatar. The shared components decode bytes rather than resolve
     * paths, so a host that does not answer this draws a flat plate everywhere and reports no error at
     * all — which is exactly what the store looked like here before it was implemented.
     */
    @Test
    fun readsTheBytesBehindAnImage() = runTest {
        val backend = IosBackend(projectsRoot = root)
        val file = IosFiles.join(root, "icon.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        IosFiles.writeBytes(file, bytes)

        assertContentEquals(bytes, backend.imageBytes(file))
        assertNull(backend.imageBytes(IosFiles.join(root, "missing.png")), "a file that is not there is null")
    }

    /** Decoding happens in memory on a phone, so a file too large to be a picture is refused outright. */
    @Test
    fun refusesAnImageTooLargeToBeOne() = runTest {
        val backend = IosBackend(projectsRoot = root)
        val file = IosFiles.join(root, "huge.png")
        IosFiles.writeBytes(file, ByteArray(9 * 1024 * 1024))

        assertNull(backend.imageBytes(file), "past the ceiling, nothing is decoded")
    }
}

/** A per-instance suffix so concurrently-run test classes cannot share a directory. */
private var counter = 0
private fun nowSuffix(): String = "${++counter}-${IosFiles.modifiedMs(NSTemporaryDirectory())
}"
