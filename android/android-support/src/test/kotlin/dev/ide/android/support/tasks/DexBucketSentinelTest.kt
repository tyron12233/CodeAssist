package dev.ide.android.support.tasks

import dev.ide.android.support.tools.Dexer
import dev.ide.android.support.tools.ToolResult
import dev.ide.build.TaskName
import dev.ide.build.TaskResult
import dev.ide.build.engine.SimpleTaskContext
import dev.ide.testkit.withTempDir
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-bucket completeness sentinel ([DexArchives.markBucketVerified]) and the concurrent scope archiving it
 * enables ([DexArchiveBuilderTask]).
 *
 * What the sentinel buys: proving a dex bucket complete used to mean listing the library jar's classes (a zip
 * central-directory read) and then stat-ing one `.dex` per class — on every build, for every library, and again in
 * the layout preview's readiness gate. These tests pin the two properties that make trusting a recorded proof
 * safe: it is only consulted when it still matches what is on disk, and a bucket that lost dex output falls back
 * to the full scan (and therefore re-dexes) rather than being accepted.
 */
class DexBucketSentinelTest {

    private fun jar(dir: Path, name: String, vararg entries: String): Path {
        Files.createDirectories(dir)
        val p = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            for (entry in entries) { z.putNextEntry(ZipEntry(entry)); z.write(entry.toByteArray()); z.closeEntry() }
        }
        return p
    }

    private fun bucketWith(dir: Path, vararg classRelPaths: String): Path {
        Files.createDirectories(dir)
        for (c in classRelPaths) {
            val dex = dir.resolve(DexArchives.dexRelOf(c))
            dex.parent?.let { Files.createDirectories(it) }
            Files.write(dex, byteArrayOf(1))
        }
        return dir
    }

    @Test
    fun anUnmarkedBucketIsNotVerifiedAndMarkingMakesItSo() {
        withTempDir("sentinel") { tmp ->
            val bucket = bucketWith(tmp.resolve("b1"), "a/A.class", "a/B.class")
            assertFalse(DexArchives.bucketVerified(bucket), "a bucket with no sentinel is not verified")
            DexArchives.markBucketVerified(bucket)
            assertTrue(DexArchives.bucketVerified(bucket), "a marked bucket is verified")
        }
    }

    /** The property that makes the fast path a real saving: a verified bucket is accepted without the caller ever
     *  having to produce the jar's class list (i.e. without opening the jar). */
    @Test
    fun aVerifiedBucketIsAcceptedWithoutListingTheJarsClasses() {
        withTempDir("sentinel") { tmp ->
            val bucket = bucketWith(tmp.resolve("b2"), "a/A.class")
            DexArchives.markBucketVerified(bucket)
            val listed = AtomicInteger(0)
            val complete = DexArchives.bucketComplete(bucket) { listed.incrementAndGet(); setOf("a/A.class") }
            assertTrue(complete, "the sentinel alone answers the completeness question")
            assertEquals(0, listed.get(), "the class list must not be requested for a verified bucket")
        }
    }

    /** A bucket that lost dex output after being marked no longer matches its own record, so the sentinel is
     *  ignored and the full per-class scan decides — the invariant that keeps a truncated bucket from being
     *  reused forever (which would silently omit a class from the APK). */
    @Test
    fun aTruncatedBucketFallsBackToTheFullScan() {
        withTempDir("sentinel") { tmp ->
            val bucket = bucketWith(tmp.resolve("b3"), "a/A.class", "a/B.class")
            DexArchives.markBucketVerified(bucket)
            assertTrue(DexArchives.bucketVerified(bucket))

            Files.delete(bucket.resolve("a/B.dex"))
            assertFalse(DexArchives.bucketVerified(bucket), "a bucket missing a dex is no longer verified")
            val listed = AtomicInteger(0)
            val complete = DexArchives.bucketComplete(bucket) { listed.incrementAndGet(); setOf("a/A.class", "a/B.class") }
            assertFalse(complete, "the full scan sees the missing class")
            assertEquals(1, listed.get(), "the full scan does ask for the class list")
        }
    }

    /** A full scan that passes records the result, so a bucket dexed before sentinels existed pays for one scan
     *  and then joins the fast path. */
    @Test
    fun aPassingFullScanRecordsTheProofForNextTime() {
        withTempDir("sentinel") { tmp ->
            val bucket = bucketWith(tmp.resolve("b4"), "a/A.class")
            assertTrue(DexArchives.bucketComplete(bucket, setOf("a/A.class")))
            assertTrue(DexArchives.bucketVerified(bucket), "the passing scan left a sentinel behind")
        }
    }

    /** Non-dexable entries (`module-info`, `package-info`, anything under `META-INF`) produce no `.dex`, and the
     *  recorded count is of dex actually written — so their absence must not invalidate the proof. */
    @Test
    fun nonDexableEntriesDoNotInvalidateTheProof() {
        withTempDir("sentinel") { tmp ->
            val bucket = bucketWith(tmp.resolve("b5"), "a/A.class")
            val classes = setOf("a/A.class", "module-info.class", "META-INF/versions/9/a/A.class")
            assertTrue(DexArchives.bucketComplete(bucket, classes))
            assertTrue(DexArchives.bucketVerified(bucket))
            assertTrue(DexArchives.bucketComplete(bucket) { error("must not be consulted") })
        }
    }

    /** Records concurrency: how many archive invocations were in flight at once, and the high-water mark. */
    private class ConcurrencyRecordingDexer : Dexer {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val archiveCalls = AtomicInteger(0)
        override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            Files.createDirectories(outDir); Files.write(outDir.resolve("classes.dex"), byteArrayOf(1)); return ToolResult.ok(emptyList())
        }
        override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
            archiveCalls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                Thread.sleep(20)          // hold the slot long enough that a shared budget would be observed
                Files.createDirectories(outDir)
                for (jarPath in inputs.filter { Files.exists(it) }) {
                    ZipFile(jarPath.toFile()).use { zf ->
                        zf.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                            val dex = outDir.resolve(e.name.removeSuffix(".class") + ".dex")
                            dex.parent?.let { Files.createDirectories(it) }
                            Files.write(dex, byteArrayOf(1))
                        }
                    }
                }
            } finally {
                inFlight.decrementAndGet()
            }
            return ToolResult.ok(emptyList())
        }
    }

    /**
     * All four scopes (project classes, sub-module jars, external libraries, `R.jar`) archive in one pass and every
     * one produces its dex, and the LIBRARY scopes' concurrency adds up to the ONE budget they share — the point of
     * sharing it, since three scopes each sizing themselves against the whole machine would multiply the planned
     * fan-out. The project archive is deliberately outside that budget (one invocation, on the critical path, heap
     * still bounded by `InProcessDexGate`), so it can add exactly one more in flight.
     */
    @Test
    fun everyScopeArchivesConcurrentlyWithinOneSharedWorkerBudget() = runBlocking {
        withTempDir("dexscopes") { tmp ->
            val libs = tmp.resolve("libs")
            val extJars = (1..6).map { jar(libs, "ext$it.jar", "ext/E$it.class") }
            val subJars = (1..2).map { jar(libs, "sub$it.jar", "sub/S$it.class") }
            val rJar = jar(libs, "R.jar", "app/R.class", "app/R\$id.class")
            // Project classes as a real class dir. These must be VALID bytecode: the project scope runs them
            // through the ASM Kotlin-metadata strip before archiving, unlike the library scopes.
            val classes = tmp.resolve("classes")
            Files.createDirectories(classes.resolve("app"))
            Files.write(classes.resolve("app/Main.class"), classBytes("app/Main"))

            val dexer = ConcurrencyRecordingDexer()
            val task = DexArchiveBuilderTask(
                TaskName(":app:dexBuilder"), projectClasses = listOf(classes), subProjectJars = subJars,
                externalJars = extJars, androidJar = tmp.resolve("android.jar"), minApi = 26, release = false,
                stagingJar = tmp.resolve("staging/project.jar"),
                projectDexRoot = tmp.resolve("proj"), subDexRoot = tmp.resolve("sub"),
                extDexRoot = tmp.resolve("ext"), dexer = dexer, dexCacheRoot = tmp.resolve("shared"),
                rJars = listOf(rJar), rDexRoot = tmp.resolve("rdex"),
            )
            assertEquals(TaskResult.Success, task.execute(SimpleTaskContext()))

            // Every scope produced dex output.
            for (root in listOf("proj", "sub", "ext", "rdex")) {
                assertTrue(hasDexUnder(tmp.resolve(root)), "scope '$root' produced no dex")
            }
            // 6 ext + 2 sub + 1 R + 1 project jar = 10 archive invocations, each exactly once.
            assertEquals(10, dexer.archiveCalls.get(), "each input is archived exactly once across the scopes")

            // The shared budget bounds the library scopes, so they cannot over-commit past the plan; the project
            // archive accounts for the one permitted extra.
            val budget = DexConcurrency.archivePlan(subJars.size + extJars.size + 1)
            assertTrue(
                dexer.maxInFlight.get() <= budget.workers + 1,
                "concurrent archives (${dexer.maxInFlight.get()}) exceeded the library budget (${budget.workers}) + the project archive",
            )
        }
    }

    /**
     * The scopes really do overlap, not merely get launched together: the PROJECT scope's invocation blocks until
     * an EXTERNAL-library invocation has started. Archived one scope after another (project first, libraries
     * after) that wait can never be satisfied, so this fails; overlapped, it passes immediately.
     *
     * Needs a budget of at least two workers to be meaningful — with one worker the scopes correctly serialize, so
     * on a machine that plans a single worker the check is skipped rather than asserting a false expectation.
     */
    @Test
    fun theProjectScopeOverlapsTheLibraryScopes() = runBlocking {
        withTempDir("dexoverlap") { tmp ->
            val extJars = (1..4).map { jar(tmp.resolve("libs"), "ext$it.jar", "ext/E$it.class") }
            val classes = tmp.resolve("classes")
            Files.createDirectories(classes.resolve("app"))
            Files.write(classes.resolve("app/Main.class"), classBytes("app/Main"))
            val budget = DexConcurrency.archivePlan(1 + extJars.size)
            if (budget.workers < 2) return@withTempDir      // a one-worker plan serializes by design

            val projectDexRoot = tmp.resolve("proj")
            val extDexRoot = tmp.resolve("ext")
            val extStarted = java.util.concurrent.CountDownLatch(1)
            val projectSawExt = java.util.concurrent.atomic.AtomicBoolean(false)
            val dexer = object : Dexer {
                override fun dex(inputs: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult =
                    ToolResult.ok(emptyList())

                override fun dexArchive(inputs: List<Path>, classpath: List<Path>, androidJar: Path, minApi: Int, release: Boolean, outDir: Path, threads: Int, desugaredLibConfig: Path?): ToolResult {
                    // Library buckets live UNDER extDexRoot (one dir per content hash); the project scope writes
                    // straight into projectDexRoot.
                    if (outDir.startsWith(extDexRoot)) extStarted.countDown()
                    if (outDir == projectDexRoot) {
                        projectSawExt.set(extStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    Files.createDirectories(outDir)
                    for (jarPath in inputs.filter { Files.exists(it) }) {
                        ZipFile(jarPath.toFile()).use { zf ->
                            zf.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { e ->
                                val dex = outDir.resolve(e.name.removeSuffix(".class") + ".dex")
                                dex.parent?.let { Files.createDirectories(it) }
                                Files.write(dex, byteArrayOf(1))
                            }
                        }
                    }
                    return ToolResult.ok(emptyList())
                }
            }
            val task = DexArchiveBuilderTask(
                TaskName(":app:dexBuilder"), projectClasses = listOf(classes), subProjectJars = emptyList(),
                externalJars = extJars, androidJar = tmp.resolve("android.jar"), minApi = 26, release = false,
                stagingJar = tmp.resolve("staging/project.jar"),
                projectDexRoot = projectDexRoot, subDexRoot = tmp.resolve("sub"), extDexRoot = extDexRoot,
                dexer = dexer, dexCacheRoot = tmp.resolve("shared"),
            )
            assertEquals(TaskResult.Success, task.execute(SimpleTaskContext()))
            assertTrue(
                projectSawExt.get(),
                "the project scope waited for a library archive and never saw one — the scopes are not overlapping",
            )
        }
    }

    private fun hasDexUnder(root: Path): Boolean = Files.isDirectory(root) &&
        Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".dex") } }

    /** A minimal but VALID class file, so the project scope's ASM pass can read it. */
    private fun classBytes(internalName: String): ByteArray {
        val cw = org.objectweb.asm.ClassWriter(0)
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }
}
