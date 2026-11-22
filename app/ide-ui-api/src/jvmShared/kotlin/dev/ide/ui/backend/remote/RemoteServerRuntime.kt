package dev.ide.ui.backend.remote

import dev.ide.ui.backend.AnalysisPreempted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The far side of [RemoteCalls]: what answers a request that is not a member's own business. The generated
 * `RemoteBackendServer` supplies the members ([dispatch]) and the flows ([flowOf], [writeFlowValue]).
 *
 * [handle] is blocking and safe from any number of threads at once; each call runs on the thread that
 * brought it.
 */
abstract class RemoteServerRuntime(private val scope: CoroutineScope) {
    /** Call member [member] of service [service] with the arguments in [input], writing what it returns. */
    protected abstract suspend fun dispatch(service: Int, member: Int, input: RemoteIn, out: RemoteOut)

    /** The flow that member is, or null when it is not one. */
    protected abstract fun flowOf(service: Int, member: Int): Flow<*>?

    protected abstract fun writeFlowValue(service: Int, member: Int, value: Any?, out: RemoteOut)

    private val running = ConcurrentHashMap<Long, Job>()

    /** A flow the near side holds: watched from the first time it is asked for. */
    private inner class Watched(val service: Int, val member: Int, val flow: Flow<*>) {
        val state: Boolean = flow is StateFlow<*>
        @Volatile var sequence = 0L
        /** Events not yet known to be read, oldest first, each with its sequence number. State flows keep none. */
        val events = ArrayDeque<Pair<Long, Any?>>()

        init {
            scope.launch {
                // A state flow's first emission is the value the near side was handed, or one newer: it is sent
                // again rather than risk the newer one being taken for the one it has.
                flow.collect { value ->
                    synchronized(this@Watched) {
                        sequence++
                        if (!state) {
                            events.addLast(sequence to value)
                            while (events.size > 512) events.removeFirst()
                        }
                    }
                    changes.value++
                }
            }
        }
    }

    private val watched = ConcurrentHashMap<Long, Watched>()
    private val changes = MutableStateFlow(0L)

    private fun watch(service: Int, member: Int): Watched? {
        val key = service.toLong() shl 32 or member.toLong()
        watched[key]?.let { return it }
        val flow = flowOf(service, member) ?: return null
        return synchronized(watched) { watched.getOrPut(key) { Watched(service, member, flow) } }
    }

    fun handle(request: ByteArray): ByteArray {
        val input = RemoteIn(request)
        input.int()
        return try {
            when (input.int()) {
                RemoteBackendWire.CALL -> call(input)
                RemoteBackendWire.FLOW_GET -> flowGet(input)
                RemoteBackendWire.POLL -> poll(input)
                RemoteBackendWire.CANCEL -> {
                    running[input.long()]?.cancel()
                    RemoteOut().apply { int(RemoteBackendWire.OK) }.done()
                }
                else -> RemoteBackendWire.failure("unknown request")
            }
        } catch (preempted: AnalysisPreempted) {
            RemoteOut().apply { int(RemoteBackendWire.PREEMPTED) }.done()
        } catch (cancelled: CancellationException) {
            RemoteOut().apply { int(RemoteBackendWire.CANCELLED) }.done()
        } catch (t: Throwable) {
            t.printStackTrace()
            RemoteBackendWire.failure(t.message ?: t.toString(), t.javaClass.simpleName)
        }
    }

    private fun call(input: RemoteIn): ByteArray {
        val id = input.long()
        val service = input.int()
        val member = input.int()
        val out = RemoteOut().apply { int(RemoteBackendWire.OK) }
        runBlocking {
            running[id] = coroutineContext.job
            try {
                dispatch(service, member, input, out)
            } finally {
                running.remove(id)
            }
        }
        return out.done()
    }

    private fun flowGet(input: RemoteIn): ByteArray {
        val service = input.int()
        val member = input.int()
        val entry = watch(service, member) ?: return RemoteBackendWire.failure("member $service/$member is not a flow")
        val out = RemoteOut().apply { int(RemoteBackendWire.OK) }
        out.long(entry.sequence)
        if (entry.state) writeFlowValue(service, member, (entry.flow as StateFlow<*>).value, out)
        // A question already standing does not list this flow: it is answered, so the next one does.
        changes.value++
        return out.done()
    }

    private fun poll(input: RemoteIn): ByteArray {
        val asked = List(input.int()) { Triple(input.int(), input.int(), input.long()) }
        val ticket = changes.value
        fun changed() = asked.filter { (service, member, seen) -> (watch(service, member)?.sequence ?: seen) != seen }
        var news = changed()
        if (news.isEmpty()) {
            // Held until something changes, or long enough that the near side asks again anyway.
            runBlocking { withTimeoutOrNull(20_000) { changes.first { it != ticket } } }
            news = changed()
        }
        val out = RemoteOut().apply { int(RemoteBackendWire.OK) }
        out.int(news.size)
        for ((service, member, seen) in news) {
            val entry = watch(service, member)!!
            val sequence: Long
            val values: List<Any?>
            if (entry.state) {
                sequence = entry.sequence
                values = listOf((entry.flow as StateFlow<*>).value)
            } else synchronized(entry) {
                sequence = entry.sequence
                values = entry.events.filter { it.first > seen }.map { it.second }
            }
            out.int(service); out.int(member); out.long(sequence)
            out.int(values.size)
            for (value in values) out.bytes(RemoteOut().also { writeFlowValue(service, member, value, it) }.done())
        }
        return out.done()
    }
}
