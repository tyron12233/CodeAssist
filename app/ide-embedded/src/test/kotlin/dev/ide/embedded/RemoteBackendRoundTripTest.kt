package dev.ide.embedded

import dev.ide.core.IdeServicesBackend
import dev.ide.core.project.ProjectManager
import dev.ide.embedded.gen.RemoteBackendGenerator
import dev.ide.testkit.withTempDir
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.remote.RemoteBackendServer
import dev.ide.ui.backend.remote.RemoteCalls
import dev.ide.ui.backend.remote.remoteIdeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole backend across the byte channel, as the iOS app has it: a real engine behind the generated far
 * side, asked through the generated near side and nothing else.
 */
class RemoteBackendRoundTripTest {

    @Test
    fun theGeneratedFilesMatchTheInterfaces() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val (near, far) = RemoteBackendGenerator().generate()
        val stale = "Run ./gradlew :ide-embedded:generateRemoteBackend: a backend interface changed."
        assertEquals(File(root, RemoteBackendGenerator.NEAR).readText(), near, stale)
        assertEquals(File(root, RemoteBackendGenerator.FAR).readText(), far, stale)
    }

    @Test
    fun aProjectIsCreatedOpenedAndEditedThroughTheChannelAlone() {
        withTempDir("ide-remote-backend") { dir ->
            val real = IdeServicesBackend(initial = null, manager = ProjectManager.desktop(dir.resolve("projects")))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val server = RemoteBackendServer(real, scope)
                val near: IdeBackend = remoteIdeBackend(RemoteCalls(server::handle, scope, Dispatchers.IO), local = real)

                val templates = near.projects.projectTemplates()
                assertEquals(real.projects.projectTemplates(), templates)
                val template = templates.firstOrNull { "java" in it.id.lowercase() } ?: templates.first()
                val before = near.projects.projectEpoch.value

                runBlocking {
                    val made = near.projects.createProject(template.id, mapOf("name" to "Crossing", "packageName" to "com.example.crossing"))
                    assertTrue(made.success, made.message)
                    // The far side's flow reaches this side without being asked for again.
                    withTimeout(30_000) { near.projects.projectEpoch.first { it != before } }

                    assertEquals("Crossing", near.project.name)
                    assertEquals(real.project, near.project)
                    val tree = near.files.fileTree(TreeViewMode.entries.first())
                    assertEquals(real.files.fileTree(TreeViewMode.entries.first()), tree)
                    fun TreeNode.all(): List<TreeNode> = listOf(this) + children.flatMap { it.all() }
                    val source = tree.all().mapNotNull { it.filePath }.firstOrNull { it.endsWith(".java") || it.endsWith(".kt") }
                    assertTrue(source != null, "the template made a source file")

                    val text = File(source).readText()
                    near.editor.updateDocument(source, text)
                    assertEquals(real.editor.analyze(source, text), near.editor.analyze(source, text))

                    near.settings.setPreference("remote.round.trip", "yes")
                    assertEquals("yes", real.settings.preference("remote.round.trip"))
                    assertEquals("yes", near.settings.preference("remote.round.trip"))
                }
            } finally {
                scope.cancel()
                real.close()
            }
        }
    }
}
