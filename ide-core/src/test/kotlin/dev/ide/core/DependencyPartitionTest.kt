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

    /** A module's SECONDARY artifact, named by a Maven classifier (one natives jar per ABI). */
    private fun classified(name: String, classifier: String) = ResolvedArtifact(
        coordinate = coord(name).copy(classifier = classifier),
        kind = ArtifactKind.JAR,
        classesRoot = FakeFile("/cache/g/$name/1.0/$name-1.0-$classifier.jar"),
    )

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
    fun eachClassifierDeclarationOwnsTheArtifactItNamed() {
        // `gdx-platform` publishes one jar per ABI under one `group:name:version`, so two declarations share
        // a `group:name` and each has to end up with its own file. Claiming per `group:name` gave the first
        // declarer both jars and left the second with an empty partition (read as "didn't resolve").
        val arm64 = classified("gdx-platform", "natives-arm64-v8a")
        val armv7 = classified("gdx-platform", "natives-armeabi-v7a")
        val out = DependencyPartition.partition(
            listOf(
                "g:gdx-platform:1.0:natives-arm64-v8a" to coord("gdx-platform").copy(classifier = "natives-arm64-v8a"),
                "g:gdx-platform:1.0:natives-armeabi-v7a" to coord("gdx-platform").copy(classifier = "natives-armeabi-v7a"),
            ),
            listOf(arm64, armv7),
        )
        assertEquals(
            mapOf(
                "g:gdx-platform:1.0:natives-arm64-v8a" to listOf("natives-arm64-v8a"),
                "g:gdx-platform:1.0:natives-armeabi-v7a" to listOf("natives-armeabi-v7a"),
            ),
            out.mapValues { (_, v) -> v.mapNotNull { it.coordinate.classifier }.sorted() },
        )
    }

    @Test
    fun aPlainDeclarationStillTakesEveryArtifactOfItsModule() {
        // Declared without a classifier, so there is no named artifact to single out: the module's whole
        // contribution belongs to that one declaration.
        val main = art("lib")
        val extra = classified("lib", "natives-arm64-v8a")
        val out = DependencyPartition.partition(listOf("g:lib:1.0" to coord("lib")), listOf(main, extra))
        assertEquals(2, out.getValue("g:lib:1.0").size, "both artifacts of the module: ${out.getValue("g:lib:1.0")}")
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
