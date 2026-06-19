package dev.ide.core

import dev.ide.deps.ArtifactKind
import dev.ide.deps.ResolvedArtifact
import dev.ide.model.Coordinate
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyPartitionTest {

    private fun art(name: String, deps: List<String> = emptyList()) = ResolvedArtifact(
        coordinate = coord(name),
        kind = ArtifactKind.JAR,
        classesRoot = FakeFile("/cache/g/$name/1.0/$name-1.0.jar"),
        dependsOn = deps.map { coord(it) },
    )

    private fun coord(name: String) = Coordinate("g", name, "1.0")

    private fun names(buckets: Map<String, List<ResolvedArtifact>>) =
        buckets.mapValues { (_, v) -> v.map { it.coordinate.name }.sorted() }

    @Test
    fun eachArtifactGoesToExactlyOneBucketAndUnionIsWholeClosure() {
        // a → b → c ; standalone d. Direct deps: a, d.
        val resolved = listOf(art("a", listOf("b")), art("b", listOf("c")), art("c"), art("d"))
        val out = DependencyPartition.partition(listOf("g:a:1.0" to coord("a"), "g:d:1.0" to coord("d")), resolved)
        assertEquals(mapOf("g:a:1.0" to listOf("a", "b", "c"), "g:d:1.0" to listOf("d")), names(out))
    }

    @Test
    fun sharedTransitiveIsClaimedByTheFirstDeclarerOnly() {
        // Both a and d depend on shared `s`. Declaration order a, d → a owns s; union still has it once.
        val resolved = listOf(art("a", listOf("s")), art("d", listOf("s")), art("s"))
        val out = DependencyPartition.partition(listOf("g:a:1.0" to coord("a"), "g:d:1.0" to coord("d")), resolved)
        assertEquals(mapOf("g:a:1.0" to listOf("a", "s"), "g:d:1.0" to listOf("d")), names(out))

        // Reverse the declaration order → d now owns the shared transitive.
        val rev = DependencyPartition.partition(listOf("g:d:1.0" to coord("d"), "g:a:1.0" to coord("a")), resolved)
        assertEquals(mapOf("g:d:1.0" to listOf("d", "s"), "g:a:1.0" to listOf("a")), names(rev))
    }

    @Test
    fun everyResolvedArtifactSurvivesAcrossTheUnion() {
        val resolved = listOf(art("a", listOf("b", "c")), art("b", listOf("c")), art("c"), art("d", listOf("c")))
        val out = DependencyPartition.partition(listOf("g:a:1.0" to coord("a"), "g:d:1.0" to coord("d")), resolved)
        assertEquals(resolved.map { it.coordinate.name }.toSet(), out.values.flatten().map { it.coordinate.name }.toSet())
        // No artifact duplicated across buckets.
        val all = out.values.flatten().map { it.coordinate.name }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun artifactReachableFromNoDeclarerIsKeptOnTheFirstBucket() {
        // `orphan` isn't reachable from declared `a` — must still land somewhere, not vanish.
        val resolved = listOf(art("a"), art("orphan"))
        val out = DependencyPartition.partition(listOf("g:a:1.0" to coord("a")), resolved)
        assertEquals(setOf("a", "orphan"), out.values.flatten().map { it.coordinate.name }.toSet())
    }

    private class FakeFile(override val path: String) : VirtualFile {
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
