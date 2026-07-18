package dev.ide.core

import java.io.EOFException
import java.io.InputStream

/** An event decoded from the app-log wire: the one-time HELLO handshake or a forwarded log record. */
sealed interface AppLogEvent {
    data class Hello(val protocolVersion: Int, val packageName: String, val pid: Int, val token: String) : AppLogEvent
    data class Record(val entry: AppLogEntry) : AppLogEvent
}

/**
 * The reader half of the app-log wire protocol produced by `:applog-runtime`'s `IdeLogBridge` (keep the two
 * in sync). The stream is length-prefixed frames — a 4-byte big-endian length, then that many UTF-8 bytes.
 * The payload is tab-separated with a leading kind field:
 * ```
 *   H \t <protocolVersion> \t <packageName> \t <pid> \t <token>
 *   L \t <timestampMs> \t <pid> \t <tid> \t <level> \t <tag> \t <message>
 * ```
 * [message] is everything after the sixth tab and may itself contain tabs and newlines.
 *
 * Pure (no Android): the socket I/O lives in `:ide-android`'s `AppLogChannelImpl`, which reads frames through
 * [readFrame] and decodes them with [parse]; this half is unit-testable on the plain JVM.
 */
object AppLogWire {
    const val PROTOCOL_VERSION = 1
    const val SOCKET_NAME = "dev.ide.codeassist.applog"

    /** A defensive cap on a single frame (a well-behaved bridge never approaches it) so a bad/hostile peer
     *  cannot make us allocate an enormous buffer. */
    private const val MAX_FRAME_BYTES = 1 shl 20 // 1 MiB

    /** Read one frame's UTF-8 payload from [input], or null at a clean end of stream. Throws on a truncated
     *  frame or an out-of-range length. */
    fun readFrame(input: InputStream): String? {
        val b0 = input.read()
        if (b0 < 0) return null // clean EOF between frames
        val b1 = input.read(); val b2 = input.read(); val b3 = input.read()
        if (b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("truncated app-log frame length")
        val len = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        if (len < 0 || len > MAX_FRAME_BYTES) throw java.io.IOException("app-log frame length out of range: $len")
        val bytes = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(bytes, read, len - read)
            if (n < 0) throw EOFException("truncated app-log frame body ($read/$len)")
            read += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Parse a frame [payload] into an [AppLogEvent], or null when it is malformed/unrecognized. */
    fun parse(payload: String): AppLogEvent? {
        if (payload.isEmpty()) return null
        return when (payload[0]) {
            'H' -> {
                val f = payload.split('\t', limit = 5)
                if (f.size < 4) return null
                AppLogEvent.Hello(
                    protocolVersion = f[1].toIntOrNull() ?: 0,
                    packageName = f[2],
                    pid = f[3].toIntOrNull() ?: 0,
                    token = f.getOrElse(4) { "" },
                )
            }
            'L' -> {
                // kind, ts, pid, tid, level, tag, message — message is the remainder (may contain tabs).
                val f = payload.split('\t', limit = 7)
                if (f.size < 6) return null
                AppLogEvent.Record(
                    AppLogEntry(
                        timestampMs = f[1].toLongOrNull() ?: 0L,
                        pid = f[2].toIntOrNull() ?: 0,
                        tid = f[3].toIntOrNull() ?: 0,
                        level = AppLogLevel.of(f[4].firstOrNull() ?: 'I'),
                        tag = f[5],
                        message = f.getOrElse(6) { "" },
                    ),
                )
            }
            else -> null
        }
    }
}
