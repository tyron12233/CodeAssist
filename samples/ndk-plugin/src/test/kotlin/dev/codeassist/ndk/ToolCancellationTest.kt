package dev.codeassist.ndk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ToolCancellationTest {

    private fun sleeper(): Process = ProcessBuilder("sleep", "30").start()

    @Test
    fun cancellingTheCoroutineKillsTheProcess() = runBlocking {
        var process: Process? = null
        val started = System.nanoTime()
        val request = async {
            killableTool { cancellation ->
                val p = sleeper().also { process = it }
                cancellation.attach(p)
                p.waitFor()
            }
        }
        delay(300)
        request.cancel()
        assertThrows<CancellationException> { runBlocking { request.await() } }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 5_000, "the request ended with its process, not 30 s later ($ms ms)")
        assertFalse(process!!.isAlive)
    }

    @Test
    fun aCancelBeforeTheProcessExistsKillsItOnAttach() {
        val cancellation = ToolCancellation()
        cancellation.cancel()
        val p = sleeper()
        cancellation.attach(p)
        assertTrue(p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(cancellation.isCancelled)
    }

    @Test
    fun aCancelAfterFinishIsANoOp() {
        val cancellation = ToolCancellation()
        val p = sleeper()
        cancellation.attach(p)
        cancellation.finish()
        cancellation.cancel()
        assertFalse(cancellation.isCancelled)
        assertTrue(p.isAlive, "a finished request no longer owns the process")
        p.destroyForcibly()
    }

    @Test
    fun anUncancelledRequestReturnsItsResult() = runBlocking {
        val result = killableTool { cancellation ->
            val p = ProcessBuilder("echo", "hi").start()
            cancellation.attach(p)
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }
        assertEquals("hi", result)
    }
}
