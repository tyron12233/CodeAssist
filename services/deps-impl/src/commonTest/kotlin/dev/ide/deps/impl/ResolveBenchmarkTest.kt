package dev.ide.deps.impl

import dev.ide.deps.ConflictPolicy
import dev.ide.deps.Repository
import dev.ide.model.Coordinate
import dev.ide.platform.ProgressReporter
import dev.ide.platform.fileInfo
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * What resolving a real Compose app's libraries costs, cold (an empty cache, everything fetched) and warm
 * (the same graph again over the cache it left), split into time spent waiting on the network and the rest.
 *
 * Skipped unless [FLAG] exists: it talks to Maven Central and Google's repository.
 */
@OptIn(ExperimentalAtomicApi::class)
class ResolveBenchmarkTest {

    private class Timed(private val inner: ArtifactFetcher) : ArtifactFetcher {
        val fetches = AtomicInt(0)
        val downloads = AtomicInt(0)
        val nanos = AtomicLong(0)
        val bytes = AtomicLong(0)

        override fun fetch(url: String): ByteArray? {
            val t = TimeSource.Monotonic.markNow()
            try {
                return inner.fetch(url)?.also { bytes.addAndFetch(it.size.toLong()) }
            } finally {
                fetches.addAndFetch(1)
                nanos.addAndFetch(t.elapsedNow().inWholeNanoseconds)
            }
        }

        override fun fetchTo(url: String, dest: String, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit): Boolean {
            val t = TimeSource.Monotonic.markNow()
            try {
                return inner.fetchTo(url, dest, onProgress).also { if (it) bytes.addAndFetch(fileInfo(dest)?.size ?: 0) }
            } finally {
                downloads.addAndFetch(1)
                nanos.addAndFetch(t.elapsedNow().inWholeNanoseconds)
            }
        }
    }

    private val silent = object : ProgressReporter {
        override fun report(fraction: Double, message: String?) {}
        override fun checkCanceled() {}
        override val isCanceled: Boolean get() = false
    }

    @Test
    fun aComposeAppResolvesColdAndWarm() {
        if (fileInfo(FLAG) == null) {
            println("$FLAG is absent; skipping the resolve benchmark")
            return
        }
        val dir = scratchPath("resolve-bench-${nowSuffix()}")
        repeat(2) { round ->
            val fetcher = Timed(HttpArtifactFetcher())
            val resolver = MavenDependencyResolver(ResolverCache(dir), ::DiskFile, fetcher)
            val t = TimeSource.Monotonic.markNow()
            val result = runBlocking { resolver.resolve(DECLARED, REPOSITORIES, ConflictPolicy.NEWEST, silent) }
            val total = t.elapsedNow().inWholeMilliseconds
            println(
                "resolve ${if (round == 0) "cold" else "warm"}: ${total}ms, ${result.resolved.size} resolved, " +
                    "${result.unresolved.size} unresolved; ${fetcher.fetches.load()} fetches + " +
                    "${fetcher.downloads.load()} downloads, ${fetcher.bytes.load() / 1024} KB, " +
                    "summed network wait ${fetcher.nanos.load() / 1_000_000}ms",
            )
        }
    }

    private companion object {
        const val FLAG = "/private/tmp/codeassist-resolve-bench"
        val REPOSITORIES = listOf(
            Repository("Maven Central", "https://repo1.maven.org/maven2"),
            Repository("Google", "https://dl.google.com/dl/android/maven2"),
        )
        val DECLARED = listOf(
            Coordinate("androidx.core", "core-ktx", "1.13.1"),
            Coordinate("androidx.activity", "activity-compose", "1.13.0"),
            Coordinate("androidx.compose.ui", "ui", "1.11.2"),
            Coordinate("androidx.compose.material3", "material3", "1.4.0"),
            Coordinate("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.0"),
        )
    }
}
