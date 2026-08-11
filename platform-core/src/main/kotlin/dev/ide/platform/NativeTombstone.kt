package dev.ide.platform

import java.io.InputStream

/**
 * A scrubbed summary of an Android native-crash tombstone.
 *
 * ## Why this exists
 * A native fault (`SIGSEGV`) kills the process below the JVM, so no Java exception is thrown and the
 * in-process crash reporter never sees it. [EngineBreadcrumb] records what the editor engine was doing, but
 * it records the op that most recently *started*, on the thread that *wrote* the crumb, so it cannot say
 * whether the engine was still busy or which thread actually faulted. The OS keeps the real answer: for a
 * process that died of `REASON_CRASH_NATIVE`, `ApplicationExitInfo.getTraceInputStream()` hands the app its
 * own tombstone as protobuf (Android 12+), which names the faulting thread, the signal and its code, the
 * fault address, and the native backtrace. `/data/tombstones` itself is unreadable without root, so this
 * stream is the only route to it.
 *
 * This parses the fields that localise such a crash and drops everything else (register dumps, memory dumps,
 * memory maps, log buffers, open file descriptors), which is also what keeps it privacy-safe: the summary
 * carries a thread name, a signal, an address, and code symbols, never file paths or source content. Path-like
 * text is reduced to a basename ([basename]), so a backtrace frame reports `libart.so`, not the APK's install
 * path.
 *
 * ## Parsing
 * A minimal reader over the protobuf wire format, so the module keeps its zero-dependency shape (no protobuf
 * runtime, no generated code). Field numbers come from `system/core/debuggerd/proto/tombstone.proto` and are
 * pinned by `NativeTombstoneTest`, which encodes a synthetic tombstone and reads it back. Unknown fields are
 * skipped, and a truncated or malformed stream yields whatever was decoded before the damage rather than
 * throwing, so a tombstone from a newer platform (or one cut short by [MAX_BYTES]) still reports what it can.
 */
data class NativeTombstone(
    /** The process ABI as the kernel saw it: `arm32`, `arm64`, `x86`, `x86_64`, `riscv64`. */
    val arch: String? = null,
    /** Signal name, for example `SIGSEGV`, `SIGABRT`, `SIGBUS`. */
    val signal: String? = null,
    /** Signal code name, for example `SEGV_MAPERR` (unmapped address) or `SEGV_ACCERR` (permissions). */
    val signalCode: String? = null,
    /** The faulting address. Small values are the signature of a dereference through a corrupt reference. */
    val faultAddress: Long? = null,
    /** The cause debuggerd inferred, for example a null dereference or a probable stack overflow. */
    val cause: String? = null,
    /** An `abort()` message, present for `SIGABRT` (an ART runtime check failure carries its reason here). */
    val abortMessage: String? = null,
    /** The thread that faulted. */
    val faultingTid: Int? = null,
    /** The faulting thread's name, for example `ide-engine`, `main`, `HeapTaskDaemon`. */
    val faultingThread: String? = null,
    /** The faulting thread's backtrace, innermost first, as `libname.so!symbol`. */
    val frames: List<String> = emptyList(),
    /** How long the process had been running when it died. */
    val uptimeSeconds: Int? = null,
) {

    /** The innermost [n] frames joined for a single analytics property. */
    fun topFrames(n: Int = 6): String = frames.take(n).joinToString(" < ")

    companion object {
        /** How much of a tombstone to read. Register and memory dumps make the whole file arbitrarily large,
         *  while the fields parsed here sit in its first few kilobytes. */
        const val MAX_BYTES: Int = 512 * 1024

        // Tombstone
        private const val F_ARCH = 1
        private const val F_TID = 6
        private const val F_UPTIME = 20
        private const val F_SIGNAL = 10
        private const val F_ABORT_MESSAGE = 14
        private const val F_CAUSES = 15
        private const val F_THREADS = 16

        // Signal
        private const val F_SIG_NAME = 2
        private const val F_SIG_CODE_NAME = 4
        private const val F_SIG_FAULT_ADDRESS = 9

        // Cause
        private const val F_CAUSE_TEXT = 1

        // map<uint32, Thread> entry
        private const val F_MAP_KEY = 1
        private const val F_MAP_VALUE = 2

        // Thread
        private const val F_THREAD_ID = 1
        private const val F_THREAD_NAME = 2
        private const val F_THREAD_BACKTRACE = 4

        // BacktraceFrame
        private const val F_FRAME_FUNCTION = 4
        private const val F_FRAME_FILE = 6

        private val ARCH = arrayOf("arm32", "arm64", "x86", "x86_64", "riscv64", "none")

        /** How many threads to keep names and backtraces for. A tombstone dumps every thread in the process,
         *  but only the faulting one is reported, and its tid is known only after the whole message is read. */
        private const val MAX_THREADS = 256
        private const val MAX_FRAMES = 16
        private const val MAX_TEXT = 200

        /**
         * Decode [input] (a tombstone protobuf) into a summary, or null if nothing recognisable was found.
         * Reads at most [limit] bytes and does not close the stream.
         */
        fun parse(input: InputStream, limit: Int = MAX_BYTES): NativeTombstone? =
            runCatching { decode(readUpTo(input, limit)) }.getOrNull()

        /** Read at most [limit] bytes of [input]. `InputStream.readNBytes` is a Java 9 API that ART only gained
         *  in API 33, below this module's floor. */
        private fun readUpTo(input: InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
            val chunk = ByteArray(16 * 1024)
            while (out.size() < limit) {
                val read = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
                if (read <= 0) break
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }

        private fun decode(bytes: ByteArray): NativeTombstone? {
            var arch: String? = null
            var signal: String? = null
            var signalCode: String? = null
            var faultAddress: Long? = null
            var cause: String? = null
            var abortMessage: String? = null
            var tid: Int? = null
            var uptime: Int? = null
            val names = HashMap<Int, String>()
            val traces = HashMap<Int, List<String>>()

            // A malformed or truncated message stops the walk; whatever was decoded up to that point is kept,
            // since the interesting fields precede the bulky dumps in wire order.
            runCatching {
                val r = Cursor(bytes, 0, bytes.size)
                while (r.hasMore()) {
                    val tag = r.varint().toInt()
                    when (tag ushr 3) {
                        F_ARCH -> arch = ARCH.getOrNull(r.varint().toInt())
                        F_TID -> tid = r.varint().toInt()
                        F_UPTIME -> uptime = r.varint().toInt()
                        F_SIGNAL -> r.message().let { s ->
                            while (s.hasMore()) {
                                val t = s.varint().toInt()
                                when (t ushr 3) {
                                    F_SIG_NAME -> signal = s.string(MAX_TEXT)
                                    F_SIG_CODE_NAME -> signalCode = s.string(MAX_TEXT)
                                    F_SIG_FAULT_ADDRESS -> faultAddress = s.varint()
                                    else -> s.skip(t and 7)
                                }
                            }
                        }
                        F_ABORT_MESSAGE -> abortMessage = scrub(r.string(MAX_TEXT))
                        F_CAUSES -> r.message().let { c ->
                            while (c.hasMore()) {
                                val t = c.varint().toInt()
                                if (t ushr 3 == F_CAUSE_TEXT) {
                                    val text = scrub(c.string(MAX_TEXT))
                                    if (cause == null && text.isNotEmpty()) cause = text
                                } else {
                                    c.skip(t and 7)
                                }
                            }
                        }
                        // Every branch must consume its field even when a cap is hit, or the walk desynchronises.
                        F_THREADS -> r.message().let { if (names.size < MAX_THREADS) readThread(it, names, traces) }
                        else -> r.skip(tag and 7)
                    }
                }
            }

            val thread = tid?.let { names[it] }
            val frames = tid?.let { traces[it] } ?: emptyList()
            if (arch == null && signal == null && thread == null && frames.isEmpty() && abortMessage == null) {
                return null
            }
            return NativeTombstone(
                arch = arch, signal = signal, signalCode = signalCode, faultAddress = faultAddress,
                cause = cause, abortMessage = abortMessage?.takeIf { it.isNotEmpty() },
                faultingTid = tid, faultingThread = thread?.takeIf { it.isNotEmpty() },
                frames = frames, uptimeSeconds = uptime,
            )
        }

        /** Read one `map<uint32, Thread>` entry into [names] and [traces], keyed by tid. */
        private fun readThread(entry: Cursor, names: MutableMap<Int, String>, traces: MutableMap<Int, List<String>>) {
            var key: Int? = null
            var id: Int? = null
            var name = ""
            val frames = ArrayList<String>(MAX_FRAMES)
            while (entry.hasMore()) {
                val tag = entry.varint().toInt()
                when (tag ushr 3) {
                    F_MAP_KEY -> key = entry.varint().toInt()
                    F_MAP_VALUE -> entry.message().let { t ->
                        while (t.hasMore()) {
                            val f = t.varint().toInt()
                            when (f ushr 3) {
                                F_THREAD_ID -> id = t.varint().toInt()
                                F_THREAD_NAME -> name = t.string(MAX_TEXT)
                                F_THREAD_BACKTRACE ->
                                    t.message().let { if (frames.size < MAX_FRAMES) frames += readFrame(it) }
                                else -> t.skip(f and 7)
                            }
                        }
                    }
                    else -> entry.skip(tag and 7)
                }
            }
            val tid = id ?: key ?: return
            names[tid] = name
            if (frames.isNotEmpty()) traces[tid] = frames
        }

        /** One backtrace frame as `libname.so!symbol`, reduced to a basename so no install path is reported. */
        private fun readFrame(frame: Cursor): String {
            var function = ""
            var file = ""
            while (frame.hasMore()) {
                val tag = frame.varint().toInt()
                when (tag ushr 3) {
                    F_FRAME_FUNCTION -> function = frame.string(MAX_TEXT)
                    F_FRAME_FILE -> file = basename(frame.string(MAX_TEXT))
                    else -> frame.skip(tag and 7)
                }
            }
            return when {
                file.isNotEmpty() && function.isNotEmpty() -> "$file!$function"
                file.isNotEmpty() -> file
                function.isNotEmpty() -> function
                else -> "?"
            }
        }

        /** The last path segment of [path]. Frames and mappings name the binary they came from by its full
         *  install path; only the binary's name is reported. */
        private fun basename(path: String): String = path.substringAfterLast('/')

        /** Reduce every path-like token in [text] to a basename, so a message quoting a file keeps its shape
         *  without reporting where the file lives. */
        private fun scrub(text: String): String =
            text.trim().split(' ').joinToString(" ") { if (it.contains('/')) basename(it) else it }
    }

    /**
     * A bounds-checked cursor over a protobuf message. Wire types: 0 varint, 1 fixed64, 2 length-delimited,
     * 5 fixed32. Types 3 and 4 (the removed group encoding) are rejected, which also stops the walk on a
     * misaligned read rather than letting it wander.
     */
    private class Cursor(private val buf: ByteArray, private var pos: Int, private val end: Int) {

        fun hasMore(): Boolean = pos < end

        fun varint(): Long {
            var shift = 0
            var value = 0L
            while (true) {
                require(pos < end && shift <= 63) { "truncated varint" }
                val b = buf[pos++].toInt() and 0xff
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
        }

        /** A nested length-delimited message as its own cursor, advancing this one past it. */
        fun message(): Cursor {
            val len = varint().toInt()
            require(len >= 0 && pos + len <= end) { "truncated message" }
            return Cursor(buf, pos, pos + len).also { pos += len }
        }

        /** A length-delimited string, truncated to [max] characters. */
        fun string(max: Int): String {
            val len = varint().toInt()
            require(len >= 0 && pos + len <= end) { "truncated string" }
            val s = String(buf, pos, minOf(len, max), Charsets.UTF_8)
            pos += len
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> advance(8)
                2 -> advance(varint().toInt())
                5 -> advance(4)
                else -> throw IllegalArgumentException("wire type $wireType")
            }
        }

        private fun advance(n: Int) {
            require(n >= 0 && pos + n <= end) { "truncated field" }
            pos += n
        }
    }
}
