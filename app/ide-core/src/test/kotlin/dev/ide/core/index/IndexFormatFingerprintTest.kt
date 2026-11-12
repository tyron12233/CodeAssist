package dev.ide.core.index

import dev.ide.core.plugins.BUILT_IN_INDEX_EXTENSIONS
import dev.ide.index.IndexScope
import dev.ide.index.impl.IndexServiceImpl
import dev.ide.testkit.TestJars
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.fail

/**
 * An indexer whose output changes must change its `version`.
 *
 * A segment is looked up by index id, index version and the content of what was indexed, and nothing else. So
 * a change to what an indexer writes that keeps its version is served from every segment written before it: the
 * ones already on a phone, the ones bundled in the APK ([dev.ide.core.PrebuiltIndexSegments]) and the ones in
 * the public repository ([LibraryIndexRepository]), whose flavor is a digest of the versions. The version bump is
 * the one signal all three regenerate from, and nothing else in the build notices a missing one.
 *
 * This indexes a fixed set of inputs with every built-in indexer and compares a hash of each one's segments
 * with the recorded one in `index-fingerprints.txt`. A different hash under the same version fails. After
 * bumping the version (or the shared codec's `FORMAT`), record the new state:
 *
 *     UPDATE_INDEX_FINGERPRINTS=1 ./gradlew :ide-core:test --tests '*IndexFormatFingerprintTest'
 *
 * The same command re-records after the fixture itself changes (a Kotlin upgrade replaces the stdlib jar).
 *
 * Not covered: a library `res/` folder. The resource index stores each declaring file's absolute path, so its
 * segments differ with the directory they were built in and have no stable hash.
 */
class IndexFormatFingerprintTest {

    @Test
    fun anIndexersOutputOnlyChangesWithItsVersion() {
        val current = fingerprint()
        if (System.getenv("UPDATE_INDEX_FINGERPRINTS") == "1") {
            Files.write(recordedFile(), current.lines())
            return
        }
        val recorded = Recorded.read(javaClass.getResourceAsStream("/index-fingerprints.txt")?.bufferedReader()?.readLines().orEmpty())
        val update = "UPDATE_INDEX_FINGERPRINTS=1 ./gradlew :ide-core:test --tests '*IndexFormatFingerprintTest'"
        if (recorded.fixture != current.fixture) {
            fail("The fixture the fingerprints were recorded from has changed (${recorded.fixture} -> ${current.fixture}). Re-record them:\n  $update")
        }
        val unbumped = ArrayList<String>()
        val stale = ArrayList<String>()
        for ((id, now) in current.indexes) {
            val was = recorded.indexes[id]
            when {
                was == null -> stale += "$id (new index)"
                was.version == now.version && was.hash != now.hash -> unbumped += "$id (still v${now.version})"
                was != now -> stale += "$id (v${was.version} -> v${now.version})"
            }
        }
        stale += (recorded.indexes.keys - current.indexes.keys).map { "$it (removed)" }
        if (unbumped.isNotEmpty()) {
            fail(
                "These indexers write different segments than before under the SAME version, so existing segments " +
                    "(on devices, bundled in the APK, in the public index repository) would be read as if they were " +
                    "current:\n  " + unbumped.joinToString("\n  ") +
                    "\nBump each one's `version` (for a shared value codec, its FORMAT), then re-record:\n  $update",
            )
        }
        if (stale.isNotEmpty()) {
            fail("index-fingerprints.txt is behind the indexers:\n  " + stale.joinToString("\n  ") + "\nRe-record it:\n  $update")
        }
    }

    private data class Entry(val version: Int, val hash: String)

    private class Recorded(val fixture: String, val indexes: Map<String, Entry>) {
        fun lines(): List<String> =
            listOf(
                "# Recorded by IndexFormatFingerprintTest: a hash of what each built-in indexer writes for a fixed set",
                "# of inputs. Do not edit by hand; see the test for when and how to re-record.",
                "fixture $fixture",
            ) + indexes.toSortedMap().map { (id, e) -> "$id v${e.version} ${e.hash}" }

        companion object {
            fun read(lines: List<String>): Recorded {
                var fixture = ""
                val indexes = HashMap<String, Entry>()
                for (line in lines) {
                    val p = line.trim().split(' ')
                    when {
                        line.startsWith("#") || p.size < 2 -> {}
                        p[0] == "fixture" -> fixture = p[1]
                        p.size == 3 -> indexes[p[0]] = Entry(p[1].removePrefix("v").toInt(), p[2])
                    }
                }
                return Recorded(fixture, indexes)
            }
        }
    }

    /** Index the fixture with every built-in indexer and hash each one's segments. */
    private fun fingerprint(): Recorded {
        var out: Recorded? = null
        withTempDir("index-fingerprint") { dir ->
            val jar = TestJars.kotlinStdlib()
            val sources = sourcesJar(dir.resolve("fixture-sources.jar"))
            val cache = dir.resolve("cache")
            val service = IndexServiceImpl(BUILT_IN_INDEX_EXTENSIONS, cache)
            try {
                runBlocking {
                    service.ensureUpToDate(
                        IndexScope(
                            libraryJars = listOf(jar), sourceArchives = listOf(sources),
                            // Fixed ids, as the content ids are on a device: no path or time reaches a segment's name.
                            stableJarIds = mapOf(jar to "fixture-jar", sources to "fixture-src"),
                        ),
                    )
                }
                check(service.status.ready) { "index build incomplete: ${service.status.message}" }
            } finally {
                service.close()
            }
            // An indexer with nothing to say about these inputs still writes an (empty) segment, so it is recorded too.
            val indexes = BUILT_IN_INDEX_EXTENSIONS.associate { ext ->
                val md = MessageDigest.getInstance("SHA-256")
                val root = cache.resolve(ext.id.value)
                val segments = if (!Files.isDirectory(root)) emptyList() else Files.walk(root).use { s ->
                    s.filter { it.toString().endsWith(".seg") }.collect(java.util.stream.Collectors.toList())
                }.sortedBy { it.fileName.toString() }
                for (seg in segments) {
                    md.update(seg.fileName.toString().toByteArray())
                    md.update(Files.readAllBytes(seg))
                }
                ext.id.value to Entry(ext.version, md.digest().take(8).joinToString("") { "%02x".format(it) })
            }
            out = Recorded(JarContentIds.compute(jar) ?: error("unreadable $jar"), indexes)
        }
        return out!!
    }

    private fun sourcesJar(jar: Path): Path {
        val files = linkedMapOf(
            "fixture/Greeter.java" to
                "package fixture;\n/** Greets. */\npublic class Greeter implements Runnable {\n" +
                "  /** Says hello to {@code name}. */\n  public String greet(String name, int times) { return name; }\n" +
                "  @Override public void run() {}\n  public static void main(String[] args) {}\n}\n",
            "fixture/Shapes.kt" to
                "package fixture\n/** A shape. */\nsealed interface Shape\n/** A circle of [radius]. */\n" +
                "data class Circle(val radius: Int) : Shape\n@Deprecated(\"old\") fun area(shape: Shape, scale: Int = 1): Int = scale\n" +
                "val Shape.label: String get() = \"shape\"\nfun main() {}\n",
        )
        ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return jar
    }

    private fun recordedFile(): Path {
        var dir = Paths.get("").toAbsolutePath()
        while (true) {
            val candidate = dir.resolve("app/ide-core/src/test/resources")
            if (Files.isDirectory(candidate)) return candidate.resolve("index-fingerprints.txt")
            dir = dir.parent ?: error("the repository root was not found above ${Paths.get("").toAbsolutePath()}")
        }
    }
}
