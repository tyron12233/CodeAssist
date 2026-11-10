package dev.ide.android.support.pack

import dev.ide.android.support.tasks.DexArchives
import dev.ide.android.support.tasks.SharedLibraryDexer
import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dex repository: a phone downloads a library version's dex instead of running D8 on it, which was most of a
 * new Compose project's first build. What must hold: a library is found by the coordinate its resolved path
 * encodes; nothing unsigned, altered or incomplete is installed; and the repository is only consulted where a
 * downloaded bucket IS the bucket this build would make.
 */
class DexRepositoryTest {
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val keys = keys()

    private class MapNet(val files: MutableMap<String, ByteArray>) : LibraryPack.Net {
        val requests = AtomicInteger()
        override fun get(url: String): ByteArray? { requests.incrementAndGet(); return files[url] }
    }

    private fun tmp(): Path = Files.createTempDirectory("dexrepo-test")

    /** A library jar at the place the resolver would put it, under `<root>/resolved-deps/…`. */
    private fun resolvedJar(root: Path, rel: String, vararg classes: String): Path {
        val jar = root.resolve("resolved-deps").resolve(rel)
        Files.createDirectories(jar.parent)
        ZipOutputStream(Files.newOutputStream(jar)).use { z ->
            for (c in classes) { z.putNextEntry(ZipEntry(c)); z.write(c.toByteArray()); z.closeEntry() }
        }
        return jar
    }

    /** Dex [jar] the way a build would (one `.dex` per class) into the cache [tagDir], verified. */
    private fun dexed(jar: Path, tagDir: Path) {
        val bucket = tagDir.resolve(DexArchives.contentHash(jar))
        for (c in DexArchives.classNamesOf(jar)) {
            val f = bucket.resolve(c.removeSuffix(".class") + ".dex"); Files.createDirectories(f.parent); Files.write(f, FakeDex.of(c))
        }
        DexArchives.markBucketVerified(bucket)
    }

    /** Stands in for D8's merge: one dex defining every class the per-class inputs define, minus [drop]. */
    private fun merger(drop: Set<String> = emptySet()): (List<Path>, Path) -> Boolean = { dexes, out ->
        val names = dexes.flatMap { DexClasses.namesOf(Files.readAllBytes(it))!! } - drop
        Files.write(out.resolve("classes.dex"), FakeDex.of(*names.toTypedArray())); true
    }

    /** Publishes [jars] to a fresh repository; returns what it serves at `https://repo/<path>`. */
    private fun publish(vararg jars: Path, key: KeyPair = keys, drop: Set<String> = emptySet()): MutableMap<String, ByteArray> {
        val tagDir = tmp()
        jars.forEach { dexed(it, tagDir) }
        val repo = tmp()
        DexRepository.publish(jars.toList(), tagDir, repo, key.private, merger(drop))
        return LibraryPack.filesOf(repo).associate { (rel, f) -> "https://repo/$rel" to Files.readAllBytes(f) }.toMutableMap()
    }

    private fun repository(net: LibraryPack.Net) = DexRepository("https://repo", keys.public, net)

    private fun wanted(jar: Path) = DexPackSource.Wanted(DexArchives.contentHash(jar), jar, DexArchives.classNamesOf(jar))

    @Test
    fun aResolvedJarsPathNamesItsCoordinate() {
        val root = Paths.get("/data/caches/.platform/caches/resolved-deps")
        assertEquals(
            LibraryCoordinate("androidx.compose.ui", "ui-android", "1.7.0") to "ui-android-1.7.0-exploded/classes.jar",
            LibraryCoordinate.of(root.resolve("androidx/compose/ui/ui-android/1.7.0/ui-android-1.7.0-exploded/classes.jar")),
        )
        assertEquals(
            LibraryCoordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.0") to "kotlin-stdlib-2.1.0.jar",
            LibraryCoordinate.of(root.resolve("org/jetbrains/kotlin/kotlin-stdlib/2.1.0/kotlin-stdlib-2.1.0.jar")),
        )
        assertNull(LibraryCoordinate.of(Paths.get("/data/.platform/kotlin/kotlin-stdlib-2.1.0.jar")), "the IDE's bundled stdlib has no coordinate")
        assertEquals("dex/${DexRepository.FLAVOR}/androidx/compose/ui/ui-android/1.7.0/ui-android-1.7.0.dex.zip",
            DexRepository.pathOf(LibraryCoordinate("androidx.compose.ui", "ui-android", "1.7.0")))
    }

    @Test
    fun aPublishedLibraryIsInstalledIntoTheSharedCache() {
        val jar = resolvedJar(tmp(), "com/example/lib/1.0/lib-1.0-exploded/classes.jar", "a/A.class", "a/B.class")
        val net = MapNet(publish(jar))
        val tagDir = tmp().resolve("minApi28-debug")
        assertEquals(1, repository(net).fetch(listOf(wanted(jar)), tagDir) {})
        val bucket = tagDir.resolve(DexArchives.contentHash(jar))
        assertTrue(DexArchives.bucketVerified(bucket) && DexArchives.isMergedBucket(bucket))
        assertEquals(setOf("a/A.class", "a/B.class"), DexClasses.namesOf(Files.readAllBytes(bucket.resolve("classes.dex"))))
    }

    /** A published dex that lacks one of the jar's classes would make that class vanish from the app. */
    @Test
    fun aMergedDexMissingAClassOfTheJarIsNotInstalled() {
        val jar = resolvedJar(tmp(), "com/example/lib/1.0/lib-1.0.jar", "a/A.class", "a/B.class")
        val tagDir = tmp()
        assertEquals(0, repository(MapNet(publish(jar, drop = setOf("a/B.class")))).fetch(listOf(wanted(jar)), tagDir) {})
        assertFalse(Files.exists(tagDir.resolve(DexArchives.contentHash(jar))))
    }

    @Test
    fun anArchiveSignedByAnotherKeyIsIgnored() {
        val jar = resolvedJar(tmp(), "com/example/lib/1.0/lib-1.0.jar", "a/A.class")
        val tagDir = tmp()
        assertEquals(0, repository(MapNet(publish(jar, key = keys()))).fetch(listOf(wanted(jar)), tagDir) {})
        assertFalse(Files.exists(tagDir.resolve(DexArchives.contentHash(jar))))
    }

    @Test
    fun anArchiveWithAnAlteredOrAddedFileIsIgnored() {
        val jar = resolvedJar(tmp(), "com/example/lib/1.0/lib-1.0.jar", "a/A.class")
        val files = publish(jar)
        val url = files.keys.single()
        val original = files.getValue(url)
        for (tampered in listOf(rewrite(original) { name, b -> if (name.endsWith("classes.dex")) FakeDex.of("a/A.class", "evil/Payload.class") else b },
                                rewrite(original, extra = "j0/evil/Payload.dex"))) {
            files[url] = tampered
            val tagDir = tmp()
            assertEquals(0, repository(MapNet(files)).fetch(listOf(wanted(jar)), tagDir) {})
            assertFalse(Files.exists(tagDir.resolve(DexArchives.contentHash(jar))))
        }
    }

    @Test
    fun aJarWhoseContentDiffersFromThePublishedOneIsDexedLocally() {
        val root = tmp()
        val published = resolvedJar(root, "com/example/lib/1.0/lib-1.0.jar", "a/A.class")
        val net = MapNet(publish(published))
        // Same coordinate on this device, different bytes (a patched or republished artifact).
        val local = resolvedJar(tmp(), "com/example/lib/1.0/lib-1.0.jar", "a/A.class", "a/Extra.class")
        assertEquals(0, repository(net).fetch(listOf(wanted(local)), tmp()) {})
    }

    @Test
    fun anUnpublishedLibraryIsAskedOnceNotOncePerBuild() {
        val jar = resolvedJar(tmp(), "com/example/none/1.0/none-1.0.jar", "a/A.class")
        val net = MapNet(mutableMapOf())
        val repo = repository(net)
        repeat(3) { assertEquals(0, repo.fetch(listOf(wanted(jar)), tmp()) {}) }
        assertEquals(1, net.requests.get())
    }

    @Test
    fun signedArchivesRejectUnsafeNames() {
        assertFalse(SignedArchive.isSafeName("../x")); assertFalse(SignedArchive.isSafeName("/x")); assertFalse(SignedArchive.isSafeName("a//b"))
        val ok = SignedArchive.write(listOf("coordinate a:b:1"), mapOf("j0/a.dex" to byteArrayOf(1)), keys.private)
        assertNotNull(SignedArchive.open(ok, keys.public)).also { assertEquals(listOf("coordinate a:b:1"), it.attributes) }
    }

    /** The IDE's own bundled jars have no coordinate: their dex ships in the app and is matched by content. */
    @Test
    fun aBundledJarIsInstalledFromTheAppsOwnPreDexedCopyThenTheRepositoryServesTheRest() {
        val stdlib = tmp().resolve("kotlin-stdlib-2.4.0.jar").also { j ->
            ZipOutputStream(Files.newOutputStream(j)).use { z -> z.putNextEntry(ZipEntry("kotlin/Unit.class")); z.write(byteArrayOf(1)); z.closeEntry() }
        }
        // The build's copy of it sits elsewhere (another project's .platform), same bytes.
        val buildsCopy = tmp().resolve("kotlin-stdlib-2.4.0.jar").also { Files.copy(stdlib, it) }
        val predexed = tmp().resolve("kotlin-stdlib.dex.zip").also { z ->
            ZipOutputStream(Files.newOutputStream(z)).use { it.putNextEntry(ZipEntry("classes.dex")); it.write(FakeDex.of("kotlin/Unit.class")); it.closeEntry() }
        }
        val lib = resolvedJar(tmp(), "com/example/lib/1.0/lib-1.0.jar", "a/A.class")
        val source = DexPackSource.chain(BundledDex(mapOf(stdlib to predexed)), repository(MapNet(publish(lib))))
        val tagDir = tmp()
        assertEquals(2, source.fetch(listOf(wanted(buildsCopy), wanted(lib)), tagDir) {})
        assertTrue(DexArchives.isMergedBucket(tagDir.resolve(DexArchives.contentHash(buildsCopy))))
        assertTrue(DexArchives.bucketVerified(tagDir.resolve(DexArchives.contentHash(lib))))
    }

    /** The build asks the repository before running D8, only at minSdk 26+, and dexes nothing it provided. */
    @Test
    fun theDexerTakesPublishedLibrariesInsteadOfDexingThem() {
        val dir = tmp()
        val lib = resolvedJar(dir, "p/lib/1.0/lib-1.0.jar", "p/Lib.class")
        val hash = DexArchives.contentHash(lib)
        val counting = CountingDexer()
        val net = MapNet(publish(lib))
        fun dexer(minApi: Int) = SharedLibraryDexer(counting, dir.resolve("android.jar"), minApi, false, dir.resolve("cache$minApi"), dexPack = repository(net))

        val d28 = dexer(28)
        assertTrue(runBlocking { d28.dexScope(listOf(lib), dir.resolve("ext28"), d28.computeUniverse(listOf(lib), dir.resolve("h28"))) })
        assertEquals(0, counting.archived.get(), "the published library was not dexed")
        assertTrue(DexArchives.isMergedBucket(dir.resolve("ext28/$hash")), "the build's bucket came from the repository, merged")

        // Below 26 a bucket depends on the whole library set: the repository is not consulted and D8 runs.
        val d24 = dexer(24)
        runBlocking { d24.dexScope(listOf(lib), dir.resolve("ext24"), d24.computeUniverse(listOf(lib), dir.resolve("h24"))) }
        assertEquals(1, counting.archived.get())
    }

    private class CountingDexer : Dexer {
        val archived = AtomicInteger()
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?) =
            ToolResult.ok(emptyList())
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            archived.incrementAndGet()
            Files.createDirectories(outDir.resolve("p")); Files.write(outDir.resolve("p/Lib.dex"), byteArrayOf(1))
            return ToolResult.ok(emptyList())
        }
    }

    /** [zip] with each entry's bytes passed through [map], plus an optional [extra] entry. */
    private fun rewrite(zip: ByteArray, extra: String? = null, map: (String, ByteArray) -> ByteArray = { _, b -> b }): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(zip.inputStream()).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    z.putNextEntry(ZipEntry(e.name)); z.write(map(e.name, input.readBytes())); z.closeEntry()
                }
            }
            extra?.let { z.putNextEntry(ZipEntry(it)); z.write(byteArrayOf(6)); z.closeEntry() }
        }
        return out.toByteArray()
    }

    /** A minimal dex file: just the header, ids and class definitions [DexClasses] reads. */
    internal object FakeDex {
        fun of(vararg classes: String): ByteArray {
            val n = classes.size
            val strings = classes.map { ("L" + it.removeSuffix(".class") + ";").toByteArray() }
            val stringIds = 0x70; val typeIds = stringIds + 4 * n; val classDefs = typeIds + 4 * n; var data = classDefs + 32 * n
            val size = data + strings.sumOf { it.size + 2 }
            val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("dex\n035\u0000".toByteArray())
            b.putInt(0x38, n); b.putInt(0x3C, stringIds); b.putInt(0x40, n); b.putInt(0x44, typeIds); b.putInt(0x60, n); b.putInt(0x64, classDefs)
            strings.forEachIndexed { i, s ->
                b.putInt(stringIds + 4 * i, data); b.putInt(typeIds + 4 * i, i); b.putInt(classDefs + 32 * i, i)
                b.put(data, s.size.toByte()); for ((j, c) in s.withIndex()) b.put(data + 1 + j, c); b.put(data + 1 + s.size, 0)
                data += s.size + 2
            }
            return b.array()
        }
    }
}
