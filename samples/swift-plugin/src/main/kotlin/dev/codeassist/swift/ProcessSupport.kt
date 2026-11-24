package dev.codeassist.swift

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Kills a tool's process when the coroutine running it is cancelled (Stop in the Run console), including a cancel that
 * lands before the process exists: [attach] then kills it at once.
 */
internal class ToolCancellation {
    private var process: Process? = null
    private var cancelled = false
    private var finished = false

    val isCancelled: Boolean
        @Synchronized get() = cancelled

    @Synchronized
    fun attach(process: Process) {
        if (cancelled) process.destroyForcibly() else this.process = process
    }

    @Synchronized
    fun cancel() {
        if (finished) return
        cancelled = true
        process?.destroyForcibly()
    }

    @Synchronized
    fun finish() {
        finished = true
        process = null
    }
}

/** Run the blocking [block] on the IO dispatcher, killing the process it [ToolCancellation.attach]es on cancellation. */
internal suspend fun <T> killableTool(block: (ToolCancellation) -> T): T = coroutineScope {
    val cancellation = ToolCancellation()
    // UNDISPATCHED so the watcher is registered before the block starts: a cancel that lands in between must still
    // reach the process.
    val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            cancellation.cancel()
        }
    }
    try {
        withContext(Dispatchers.IO) { block(cancellation) }
    } finally {
        cancellation.finish()
        watcher.cancel()
    }
}

/** Decodes UTF-8 arriving in arbitrary chunks, carrying a character split across two of them. */
internal class Utf8Chunks {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray, length: Int): String {
        val input = ByteBuffer.allocate(pending.size + length).put(pending).put(bytes, 0, length)
        input.flip()
        val out = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, out, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        out.flip()
        return out.toString()
    }

    fun finish(): String {
        val input = ByteBuffer.wrap(pending)
        val out = CharBuffer.allocate(pending.size + 1)
        decoder.decode(input, out, true)
        decoder.flush(out)
        pending = ByteArray(0)
        out.flip()
        return out.toString()
    }
}

/** Re-splits raw program output into lines for the build log, when there is no console to stream to. */
internal class LineSplitter(private val log: (String) -> Unit) {
    private val pending = StringBuilder()

    @Synchronized
    fun append(text: String) {
        pending.append(text)
        var nl = pending.indexOf("\n")
        while (nl >= 0) {
            log(pending.substring(0, nl).trimEnd('\r'))
            pending.delete(0, nl + 1)
            nl = pending.indexOf("\n")
        }
    }

    @Synchronized
    fun flush() {
        if (pending.isNotEmpty()) log(pending.toString().trimEnd('\r'))
        pending.setLength(0)
    }
}
