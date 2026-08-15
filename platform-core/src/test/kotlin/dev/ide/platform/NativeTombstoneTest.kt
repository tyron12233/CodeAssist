package dev.ide.platform

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NativeTombstone] reads the OS tombstone protobuf with a hand-written wire-format parser, so its field
 * numbers are an assumption about `system/core/debuggerd/proto/tombstone.proto` that nothing else checks.
 * These encode a synthetic tombstone and read it back, which pins those numbers, the reduction of paths to
 * basenames, and the tolerance the parser needs to keep working against a newer platform: unknown fields are
 * skipped, capped repeated fields still consume their bytes, and a truncated stream reports what it decoded.
 */
class NativeTombstoneTest {

    @Test
    fun readsSignalFaultingThreadAndBacktrace() {
        val engineFrames =
            frame("art::artInstanceOfFromCode(art::mirror::Object*)", "/apex/com.android.art/lib64/libart.so") +
                frame("art::Thread::DumpState", "/apex/com.android.art/lib64/libartbase.so")
        val bytes =
            varint(F_ARCH, 1) + // ARM64
                varint(F_PID, 1234) +
                varint(F_TID, 1300) +
                len(F_SIGNAL, varint(1, 11) + str(2, "SIGSEGV") + varint(3, 1) + str(4, "SEGV_MAPERR") + varint(9, 7)) +
                str(F_ABORT_MESSAGE, "check failed in /data/app/~~ab==/dev.ide.assist-1/base.apk") +
                len(F_CAUSES, str(1, "stack pointer is in a non-existent map; likely due to stack overflow")) +
                thread(1234, "main", frame("__epoll_pwait", "/apex/com.android.runtime/lib64/bionic/libc.so")) +
                thread(1300, "ide-engine", engineFrames) +
                len(F_MEMORY_MAPPINGS, varint(1, 0x7000) + str(7, "/data/app/~~ab==/dev.ide.assist-1/base.apk")) +
                varint(F_UPTIME, 143)

        val t = assertNotNull(NativeTombstone.parse(ByteArrayInputStream(bytes)))
        assertEquals("arm64", t.arch)
        assertEquals("SIGSEGV", t.signal)
        assertEquals("SEGV_MAPERR", t.signalCode)
        assertEquals(7L, t.faultAddress, "a small fault address is the corrupt-reference signature")
        assertEquals(1300, t.faultingTid)
        assertEquals("ide-engine", t.faultingThread, "the faulting thread is threads[tid], not the first thread")
        assertEquals(143, t.uptimeSeconds)
        assertTrue(t.cause!!.endsWith("likely due to stack overflow"), "cause was ${t.cause}")
        assertEquals(
            listOf(
                "libart.so!art::artInstanceOfFromCode(art::mirror::Object*)",
                "libartbase.so!art::Thread::DumpState",
            ),
            t.frames,
        )
        assertEquals(
            "libart.so!art::artInstanceOfFromCode(art::mirror::Object*) < libartbase.so!art::Thread::DumpState",
            t.topFrames(),
        )
    }

    @Test
    fun reducesPathsToBasenames() {
        val bytes =
            varint(F_TID, 9) +
                str(F_ABORT_MESSAGE, "opened /storage/emulated/0/codeassist/projects/Secret/app/Main.kt") +
                thread(9, "main", frame("", "/data/app/~~q==/dev.ide.assist-2/oat/arm64/base.odex"))

        val t = assertNotNull(NativeTombstone.parse(ByteArrayInputStream(bytes)))
        assertEquals("opened Main.kt", t.abortMessage, "a path in an abort message must not be reported")
        assertEquals(listOf("base.odex"), t.frames)
    }

    @Test
    fun skipsUnknownFieldsAndStillReadsLaterOnes() {
        // A newer platform adds fields this parser has never heard of, in every wire type it can use.
        val bytes =
            varint(F_ARCH, 3) +
                varint(900, 1) +
                len(901, str(1, "something new")) +
                fixed64(902) +
                fixed32(903) +
                varint(F_TID, 5) +
                thread(5, "RenderThread", frame("", "libhwui.so")) +
                varint(F_UPTIME, 61)

        val t = assertNotNull(NativeTombstone.parse(ByteArrayInputStream(bytes)))
        assertEquals("x86_64", t.arch)
        assertEquals("RenderThread", t.faultingThread)
        assertEquals(61, t.uptimeSeconds, "a field after the unknown ones must still be reached")
    }

    @Test
    fun cappedBacktraceStillConsumesItsBytes() {
        // 20 frames against a cap of 16: the extra frames must be read and dropped, not left in the stream,
        // or every field after them decodes as garbage.
        val many = (1..20).fold(ByteArray(0)) { acc, i -> acc + frame("f$i", "lib$i.so") }
        val bytes = varint(F_TID, 7) + thread(7, "ide-engine", many) + varint(F_UPTIME, 99)

        val t = assertNotNull(NativeTombstone.parse(ByteArrayInputStream(bytes)))
        assertEquals(16, t.frames.size)
        assertEquals("lib1.so!f1", t.frames.first(), "frames are kept innermost-first")
        assertEquals("ide-engine", t.faultingThread)
        assertEquals(99, t.uptimeSeconds, "the thread's trailing frames must have been consumed")
    }

    @Test
    fun truncatedStreamReportsWhatWasDecoded() {
        val full =
            varint(F_ARCH, 1) +
                varint(F_TID, 3) +
                len(F_SIGNAL, str(2, "SIGBUS") + str(4, "BUS_ADRALN")) +
                thread(3, "ide-engine", frame("memcpy", "libc.so"))
        // Cut inside the threads map, which is what a tombstone larger than the read cap looks like.
        val cut = full.copyOf(full.size - 12)

        val t = assertNotNull(NativeTombstone.parse(ByteArrayInputStream(cut)))
        assertEquals("SIGBUS", t.signal, "fields before the truncation must survive it")
        assertEquals("BUS_ADRALN", t.signalCode)
        assertNull(t.faultingThread, "a thread that was cut off must not be reported")
    }

    @Test
    fun garbageAndEmptyInputYieldNull() {
        assertNull(NativeTombstone.parse(ByteArrayInputStream(ByteArray(0))))
        assertNull(NativeTombstone.parse(ByteArrayInputStream(ByteArray(64) { 0xff.toByte() })))
    }

    @Test
    fun readCapBoundsTheParse() {
        val bytes = varint(F_ARCH, 1) + varint(F_TID, 4) + thread(4, "main", frame("x", "libc.so"))
        // A cap smaller than the message must not throw; it decodes the prefix it managed to read.
        val t = NativeTombstone.parse(ByteArrayInputStream(bytes), limit = 4)
        assertTrue(t == null || t.faultingThread == null, "a 4-byte prefix cannot name a thread")
    }

    // Tombstone field numbers under test.
    private val F_ARCH = 1
    private val F_PID = 5
    private val F_TID = 6
    private val F_SIGNAL = 10
    private val F_ABORT_MESSAGE = 14
    private val F_CAUSES = 15
    private val F_THREADS = 16
    private val F_MEMORY_MAPPINGS = 17
    private val F_UPTIME = 20

    /** One `map<uint32, Thread>` entry: key 1, value 2 wrapping `Thread{id 1, name 2, current_backtrace 4}`. */
    private fun thread(tid: Int, name: String, frames: ByteArray): ByteArray =
        len(F_THREADS, varint(1, tid.toLong()) + len(2, varint(1, tid.toLong()) + str(2, name) + frames))

    /** One `repeated BacktraceFrame current_backtrace = 4` element: `function_name` 4, `file_name` 6. */
    private fun frame(function: String, file: String): ByteArray =
        len(4, varint(2, 0xdead) + str(4, function) + str(6, file))

    private fun tag(field: Int, wire: Int): ByteArray = raw((field.toLong() shl 3) or wire.toLong())

    private fun varint(field: Int, value: Long): ByteArray = tag(field, 0) + raw(value)

    private fun len(field: Int, payload: ByteArray): ByteArray =
        tag(field, 2) + raw(payload.size.toLong()) + payload

    private fun str(field: Int, value: String): ByteArray = len(field, value.toByteArray(Charsets.UTF_8))

    private fun fixed64(field: Int): ByteArray = tag(field, 1) + ByteArray(8)

    private fun fixed32(field: Int): ByteArray = tag(field, 5) + ByteArray(4)

    private fun raw(value: Long): ByteArray {
        var v = value
        val out = ArrayList<Byte>()
        while (true) {
            val b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.add(b.toByte())
                break
            }
            out.add((b or 0x80).toByte())
        }
        return out.toByteArray()
    }
}
