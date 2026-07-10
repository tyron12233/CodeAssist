package dev.ide.core

import kotlinx.coroutines.runBlocking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Repro: a `suspend` function called from a non-suspend context must be flagged (`kt.suspendContext`) through
 * the REAL wired engine (with a persistent index), not just the standalone analyzer. Exercises both a
 * same-file callee and a cross-file (indexed) callee — the latter resolves the suspend function through the
 * persistent `KotlinCallableIndex`.
 */
class KotlinSuspendDiagnosticTest {

    private val root = createTempDirectory("kotlin-suspend")
    private var services: IdeServices? = null

    @AfterTest fun tearDown() { services?.close(); root.toFile().deleteRecursively() }

    private fun write(rel: String, content: String): Path {
        val f = root.resolve(rel); Files.createDirectories(f.parent); Files.writeString(f, content); return f
    }

    private fun awaitIndexReady(s: IdeServices) {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline && !s.indexService.status.ready) Thread.sleep(50)
    }

    private fun bootstrap(): IdeServices {
        val s = IdeServices.bootstrapJavaDemo(root).also { services = it }
        // A saved (indexed) suspend function in the core module — the cross-file callee.
        write("core/src/main/java/com/example/core/Work.kt", "package com.example.core\nsuspend fun doWork() {}")
        s.invalidateSyntheticClasses()
        awaitIndexReady(s)
        return s
    }

    @Test fun sameFileSuspendCallIsFlagged() {
        val s = bootstrap()
        val probe = root.resolve("core/src/main/java/com/example/core/SameFile.kt")
        val text = "package com.example.core\nfun test() { h() }\nsuspend fun h() {}"
        val diags = runBlocking { s.analyzeDiagnostics(probe, text) }
        assertTrue(diags.any { it.code == "kt.suspendContext" }, "same-file suspend call must be flagged; got ${diags.map { it.code to it.message }}")
    }

    @Test fun crossFileIndexedSuspendCallIsFlagged() {
        val s = bootstrap()
        val probe = root.resolve("core/src/main/java/com/example/core/CrossFile.kt")
        // `doWork` is the saved/indexed suspend function from Work.kt — resolved via the persistent index.
        val text = "package com.example.core\nfun test() { doWork() }"
        val diags = runBlocking { s.analyzeDiagnostics(probe, text) }
        assertTrue(diags.any { it.code == "kt.suspendContext" }, "cross-file (indexed) suspend call must be flagged; got ${diags.map { it.code to it.message }}")
    }
}
