package dev.ide.platform.impl

import dev.ide.platform.Topic
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageBusTest {

    fun interface Counter {
        fun inc(by: Int)
    }

    private val topic = Topic("test.counter", Counter::class.java)

    @Test
    fun broadcastsToAllSubscribersInSubscriptionOrder() {
        val bus = MessageBusImpl()
        val seen = CopyOnWriteArrayList<String>()
        bus.connect().subscribe(topic, Counter { by -> seen.add("a:$by") })
        bus.connect().subscribe(topic, Counter { by -> seen.add("b:$by") })

        bus.syncPublisher(topic).inc(5)

        assertEquals(listOf("a:5", "b:5"), seen.toList())
    }

    @Test
    fun noSubscribersIsANoOp() {
        val bus = MessageBusImpl()
        bus.syncPublisher(topic).inc(1) // must not throw
    }

    @Test
    fun disposingConnectionUnsubscribes() {
        val bus = MessageBusImpl()
        val seen = CopyOnWriteArrayList<Int>()
        val conn = bus.connect()
        conn.subscribe(topic, Counter { by -> seen.add(by) })

        bus.syncPublisher(topic).inc(1)
        conn.dispose()
        bus.syncPublisher(topic).inc(2)

        assertEquals(listOf(1), seen.toList())
    }

    @Test
    fun publisherIsStableAcrossCalls() {
        val bus = MessageBusImpl()
        assertTrue(bus.syncPublisher(topic) === bus.syncPublisher(topic))
    }

    @Test
    fun faultyListenerDoesNotBlockOthersAndErrorSurfaces() {
        val bus = MessageBusImpl()
        val seen = CopyOnWriteArrayList<Int>()
        val conn = bus.connect()
        conn.subscribe(topic, Counter { _ -> throw IllegalStateException("boom") })
        conn.subscribe(topic, Counter { by -> seen.add(by) })

        val ex = assertFailsWith<IllegalStateException> { bus.syncPublisher(topic).inc(7) }

        assertEquals("boom", ex.message)
        assertEquals(listOf(7), seen.toList()) // the second listener still ran
    }

    @Test
    fun subscribingOnDisposedConnectionFails() {
        val bus = MessageBusImpl()
        val conn = bus.connect()
        conn.dispose()
        assertFailsWith<IllegalStateException> { conn.subscribe(topic, Counter { }) }
    }
}
