package dev.ide.ui.backend.remote

import dev.ide.ui.backend.AnalysisPreempted
import dev.ide.ui.concurrent.UiLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * The whole backend across a byte channel: what every request and answer of it opens with.
 *
 * The other protocols in this package each carry one service's few calls, written by hand. This one carries
 * every member of `IdeBackend`, and its two ends (`RemoteIdeBackend` here, `RemoteBackendServer` on a JVM) are
 * generated from the interfaces themselves, so a host whose engine is on the far side of a channel is given
 * the engine's own answers to everything rather than the part someone got round to.
 */
object RemoteBackendWire {
    /** "IDE1". */
    const val MAGIC = 0x49444531

    internal const val CALL = 0
    internal const val FLOW_GET = 1
    internal const val POLL = 2
    internal const val CANCEL = 3

    internal const val OK = 0
    internal const val FAILED = 1
    internal const val PREEMPTED = 2
    internal const val CANCELLED = 3

    /** Whether [request] is one of this protocol's. */
    fun handles(request: ByteArray): Boolean = request.size >= 8 && RemoteIn(request).int() == MAGIC

    /** The answer of a far side that cannot answer, which the near side raises as an error with [message]. */
    fun failure(message: String, type: String = ""): ByteArray =
        RemoteOut().apply { int(FAILED); string(type); string(message) }.done()
}

/** A call the far side failed: [type] is the simple name of what it threw there. */
class RemoteBackendException(val type: String, message: String) : RuntimeException(message)

/**
 * The near side's calls, which the generated `RemoteIdeBackend` is written against.
 *
 * A plain member is asked for on the calling thread and waited for, as it would be of an engine in the same
 * process. A `suspend` member is asked for on [io] and can be abandoned: cancelling the caller tells the far
 * side to stop. A flow is read once for its value and then kept current by one standing question ([pump]),
 * which the far side answers when any flow this side holds has changed.
 */
class RemoteCalls(
    private val channel: (ByteArray) -> ByteArray,
    private val scope: CoroutineScope,
    /** Where a call waits for its answer. Calls block, so not a pool sized for work that does not. */
    private val io: CoroutineDispatcher,
    /** Whether the calling thread is the one the UI is drawn on; only asked while [tracing]. */
    private val onUiThread: () -> Boolean = { false },
) {
    private val lock = UiLock()
    private var lastId = 0L
    private fun nextId(): Long = lock.withLock { ++lastId }

    private fun request(kind: Int, write: (RemoteOut) -> Unit): ByteArray =
        RemoteOut().apply { int(RemoteBackendWire.MAGIC); int(kind); write(this) }.done()

    private fun answer(bytes: ByteArray): RemoteIn {
        val reply = RemoteIn(bytes)
        if (reply.size < 4) throw RemoteBackendException("", "The backend did not answer.")
        when (reply.int()) {
            RemoteBackendWire.OK -> return reply
            RemoteBackendWire.PREEMPTED -> throw AnalysisPreempted()
            RemoteBackendWire.CANCELLED -> throw CancellationException("The backend abandoned the call.")
            else -> {
                val type = reply.string().orEmpty()
                throw RemoteBackendException(type, reply.string() ?: "The backend did not answer.")
            }
        }
    }

    /** One member called and waited for on this thread. */
    fun call(service: Int, member: Int, write: (RemoteOut) -> Unit): RemoteIn {
        if (!tracing) return answer(channel(request(RemoteBackendWire.CALL) { it.long(nextId()); it.int(service); it.int(member); write(it) }))
        val started = TimeSource.Monotonic.markNow()
        try {
            return answer(channel(request(RemoteBackendWire.CALL) { it.long(nextId()); it.int(service); it.int(member); write(it) }))
        } finally {
            val micros = started.elapsedNow().inWholeMicroseconds
            val ui = onUiThread()
            lock.withLock {
                val entry = traced.getOrPut(service.toLong() shl 32 or member.toLong()) { LongArray(4) }
                entry[0]++
                entry[1] += micros
                if (ui) { entry[2]++; entry[3] += micros }
            }
        }
    }

    // ---- measuring -----------------------------------------------------------------------------------------

    /** While set, every waited-for call is counted and timed by member; see [traced]. */
    @kotlin.concurrent.Volatile
    var tracing = false
    private val traced = HashMap<Long, LongArray>()

    /** One member's waited-for calls while [tracing]: how many and how long in all, and of those the UI thread's. */
    class Traced(val service: Int, val member: Int, val calls: Long, val micros: Long, val uiCalls: Long, val uiMicros: Long)

    /** What was counted since the last ask, the most time on the UI thread first; the counts start again. */
    fun traced(): List<Traced> = lock.withLock {
        traced.map { (key, v) -> Traced((key shr 32).toInt(), key.toInt(), v[0], v[1], v[2], v[3]) }.also { traced.clear() }
    }.sortedByDescending { it.uiMicros * 1_000_000 + it.micros }

    /** One `suspend` member called. Cancelling the caller abandons the wait and tells the far side to stop. */
    suspend fun callSuspend(service: Int, member: Int, write: (RemoteOut) -> Unit): RemoteIn {
        val id = nextId()
        val bytes = request(RemoteBackendWire.CALL) { it.long(id); it.int(service); it.int(member); write(it) }
        val pending = scope.async(io) { channel(bytes) }
        try {
            return answer(pending.await())
        } catch (cancelled: CancellationException) {
            if (!pending.isCompleted) scope.launch(io) { runCatching { channel(request(RemoteBackendWire.CANCEL) { it.long(id) }) } }
            throw cancelled
        }
    }

    // ---- flows ---------------------------------------------------------------------------------------------

    private class Held(val service: Int, val member: Int, var seen: Long, val deliver: (RemoteIn) -> Unit)

    private val held = ArrayList<Held>()
    private var pumping = false

    /** The flow's sequence number and, for a state flow, its value now. Starts the far side watching it. */
    private fun get(service: Int, member: Int): RemoteIn =
        answer(channel(request(RemoteBackendWire.FLOW_GET) { it.int(service); it.int(member) }))

    private fun hold(entry: Held) {
        val start = lock.withLock {
            held.add(entry)
            !pumping.also { pumping = true }
        }
        if (start) scope.launch(io) { pump() }
    }

    /** A state flow of the far side: its value now, and every value it takes after. */
    fun <T> stateFlow(service: Int, member: Int, read: (RemoteIn) -> T): StateFlow<T> {
        val first = get(service, member)
        val seen = first.long()
        val flow = MutableStateFlow(read(first))
        hold(Held(service, member, seen) { flow.value = read(it) })
        return flow
    }

    /** A flow of events of the far side: every one it emits from now on. */
    fun <T> events(service: Int, member: Int, read: (RemoteIn) -> T): Flow<T> {
        val seen = get(service, member).long()
        val flow = MutableSharedFlow<T>(extraBufferCapacity = 256)
        hold(Held(service, member, seen) { flow.tryEmit(read(it)) })
        return flow
    }

    /**
     * The standing question: "what has changed since what I have". The far side holds it until something has,
     * so a quiet backend costs one waiting call, and a busy one is asked again a frame later.
     */
    private suspend fun pump() {
        while (scope.isActive) {
            val asking = lock.withLock { held.toList() }
            val reply = try {
                answer(channel(request(RemoteBackendWire.POLL) { out ->
                    out.int(asking.size)
                    for (entry in asking) { out.int(entry.service); out.int(entry.member); out.long(entry.seen) }
                }))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                delay(500)
                continue
            }
            repeat(reply.int()) {
                val service = reply.int()
                val member = reply.int()
                val seen = reply.long()
                val entry = asking.firstOrNull { it.service == service && it.member == member }
                // Each value is behind its length, so one this side cannot read does not lose the rest.
                repeat(reply.int()) {
                    val value = RemoteIn(reply.bytes())
                    runCatching { entry?.deliver?.invoke(value) }
                }
                entry?.seen = seen
            }
            delay(16)
        }
    }
}
