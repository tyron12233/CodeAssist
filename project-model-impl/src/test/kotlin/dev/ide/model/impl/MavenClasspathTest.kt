package dev.ide.model.impl

import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.MavenClasspath
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit coverage for the shared classpath version-dedup used by both the build and the editor classpaths. */
class MavenClasspathTest {

    private fun entry(path: String, kind: ClasspathEntryKind = ClasspathEntryKind.LIBRARY) =
        ClasspathEntry(PathOnlyFile(path), kind)

    private fun paths(items: List<ClasspathEntry>) = MavenClasspath.resolveVersionConflicts(items).map { it.root.path }

    @Test
    fun keepsNewestVersionOfOneArtifact() {
        val base = "/cache/resolved-deps/androidx/collection/collection"
        val result = paths(listOf(entry("$base/1.1.0/collection-1.1.0.jar"), entry("$base/1.4.0/collection-1.4.0.jar")))
        assertEquals(listOf("$base/1.4.0/collection-1.4.0.jar"), result)
    }

    @Test
    fun dedupsAcrossPlainJarAndExplodedAar() {
        // An artifact can appear as a plain jar in one closure and an exploded-AAR classes.jar in another —
        // both carry the same artifact-dir key, so the older one is still dropped.
        val base = "/cache/resolved-deps/androidx/activity/activity"
        val result = paths(listOf(
            entry("$base/1.7.0/activity-1.7.0.jar"),
            entry("$base/1.9.3/activity-1.9.3-exploded/classes.jar"),
        ))
        assertEquals(listOf("$base/1.9.3/activity-1.9.3-exploded/classes.jar"), result)
    }

    @Test
    fun nonMavenPathsPassThroughUntouched() {
        // Module outputs / local jars / the SDK aren't Maven-shaped → never collapsed, even same file name.
        val items = listOf(
            entry("/project/app/build/classes", ClasspathEntryKind.MODULE_OUTPUT),
            entry("/sdk/android.jar", ClasspathEntryKind.SDK_BOOTCLASSPATH),
            entry("/libs/local.jar"),
        )
        assertEquals(items.map { it.root.path }, paths(items))
    }

    @Test
    fun distinctArtifactsAllSurvive() {
        val ui = "/cache/resolved-deps/androidx/compose/ui/ui/1.7.5/ui-1.7.5-exploded/classes.jar"
        val foundation = "/cache/resolved-deps/androidx/compose/foundation/foundation/1.7.5/foundation-1.7.5-exploded/classes.jar"
        val result = paths(listOf(entry(ui), entry(foundation)))
        assertTrue(ui in result && foundation in result, "different artifacts must both remain: $result")
    }

    /** A [VirtualFile] that only carries a [path] — all the dedup logic reads. */
    private class PathOnlyFile(override val path: String) : VirtualFile {
        override val name get() = path.substringAfterLast('/')
        override val isDirectory = false
        override val exists = true
        override val length = 0L
        override fun parent(): VirtualFile? = null
        override fun children(): List<VirtualFile> = emptyList()
        override fun contentHash(): ContentHash = ContentHash(path)
        override fun readBytes(): ByteArray = ByteArray(0)
        override fun readText(): CharSequence = ""
    }
}
