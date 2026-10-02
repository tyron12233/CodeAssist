package dev.ide.ios

import dev.ide.deps.impl.ArtifactFetcher
import dev.ide.deps.impl.HttpArtifactFetcher
import dev.ide.model.Coordinate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * What resolving a real Compose app's libraries for the PREVIEW costs, cold and then warm, with every request
 * counted by kind. The preview resolve swaps androidx Compose for Compose Multiplatform, and finding each
 * equivalent is where its time went.
 *
 * Skipped unless [FLAG] exists: it talks to Maven Central and Google's repository.
 */
class PreviewResolveBenchmarkTest {

    private class Counting(private val inner: ArtifactFetcher) : ArtifactFetcher {
        val urls = ArrayList<Pair<String, Long>>()
        override fun fetch(url: String): ByteArray? {
            val t = TimeSource.Monotonic.markNow()
            try { return inner.fetch(url) } finally { synchronizedAdd(url, t.elapsedNow().inWholeMilliseconds) }
        }
        override fun fetchTo(url: String, dest: String, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): Boolean {
            val t = TimeSource.Monotonic.markNow()
            try { return inner.fetchTo(url, dest, onProgress) } finally { synchronizedAdd(url, t.elapsedNow().inWholeMilliseconds) }
        }
        private val lock = platform.Foundation.NSLock()
        private fun synchronizedAdd(url: String, ms: Long) { lock.lock(); try { urls.add(url to ms) } finally { lock.unlock() } }
    }

    @Test
    fun previewClasspathIsMeasured() {
        if (!IosFiles.exists(FLAG)) {
            println("$FLAG is absent; skipping the preview resolve benchmark")
            return
        }
        val root = IosFiles.join(platform.Foundation.NSTemporaryDirectory(), "preview-bench-" + kotlin.random.Random.nextLong().toULong().toString(16))
        IosFiles.mkdirs(root)
        repeat(2) { round ->
            val fetcher = Counting(HttpArtifactFetcher())
            val deps = IosPreviewDependencies(root, fetcher)
            val t = TimeSource.Monotonic.markNow()
            val jars = runBlocking { deps.classpath(DECLARED) }
            println("preview ${if (round == 0) "cold" else "warm"}: ${t.elapsedNow().inWholeMilliseconds}ms, ${jars.size} jars, ${fetcher.urls.size} requests, summed ${fetcher.urls.sumOf { it.second }}ms")
            val byKind = fetcher.urls.groupBy { it.first.substringAfterLast('.') }
            for ((k, v) in byKind) println("  .$k: ${v.size} requests, ${v.sumOf { it.second }}ms")
            if (round == 1) fetcher.urls.forEach { println("  warm fetch ${it.second}ms ${it.first}") }
        }
    }

    private companion object {
        const val FLAG = "/private/tmp/codeassist-resolve-bench"
        val DECLARED = listOf(
            Coordinate("androidx.core", "core-ktx", "1.13.1"),
            Coordinate("androidx.activity", "activity-compose", "1.13.0"),
            Coordinate("androidx.compose.ui", "ui", "1.11.2"),
            Coordinate("androidx.compose.material3", "material3", "1.4.0"),
            Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.0"),
        )
    }
}
