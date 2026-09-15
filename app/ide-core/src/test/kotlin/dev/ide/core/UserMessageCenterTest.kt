package dev.ide.core

import dev.ide.core.notify.UserMessageCenter
import dev.ide.platform.notify.MessageSeverity
import dev.ide.platform.notify.UserMessage
import dev.ide.platform.notify.error
import dev.ide.platform.notify.info
import dev.ide.platform.notify.warn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The engine half of "a plugin can tell the user something". The rules worth pinning are the ones that only
 * show up under load or misuse: an unbounded queue, a progress row nothing ever removes, a plugin action that
 * throws into the shell.
 */
class UserMessageCenterTest {

    @Test
    fun `a posted message is queued with an id the shell can dismiss it by`() {
        val center = UserMessageCenter()
        center.info("Toolchain unpacked")

        val posted = center.messages.value.single()
        assertEquals("Toolchain unpacked", posted.message.text)
        assertEquals(MessageSeverity.INFO, posted.message.severity)

        center.dismiss(posted.id)
        assertTrue(center.messages.value.isEmpty())
    }

    @Test
    fun `the severity helpers post what they say`() {
        val center = UserMessageCenter()
        center.info("i")
        center.warn("w")
        center.error("e")

        assertEquals(
            listOf(MessageSeverity.INFO, MessageSeverity.WARNING, MessageSeverity.ERROR),
            center.messages.value.map { it.message.severity },
        )
    }

    /** A plugin in a loop must not be able to grow the queue without bound, nor bury what it just said. */
    @Test
    fun `the queue is capped and keeps the most recent messages`() {
        val center = UserMessageCenter()
        repeat(20) { center.info("message $it") }

        val queued = center.messages.value
        assertEquals(8, queued.size)
        assertEquals("message 12", queued.first().message.text, "the oldest are dropped, not the newest")
        assertEquals("message 19", queued.last().message.text)
    }

    @Test
    fun `ids stay unique across the cap, so a dismiss cannot hit the wrong message`() {
        val center = UserMessageCenter()
        repeat(20) { center.info("message $it") }

        val ids = center.messages.value.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `performing a message's action runs it and takes the message off the queue`() {
        val center = UserMessageCenter()
        var ran = 0
        center.show(UserMessage("Open settings", actionLabel = "Open") { ran++ })

        center.performAction(center.messages.value.single().id)

        assertEquals(1, ran)
        assertTrue(center.messages.value.isEmpty())
    }

    /** The action is a plugin's own code, so a throw must cost the message and nothing else. */
    @Test
    fun `an action that throws does not propagate into the shell`() {
        val center = UserMessageCenter()
        center.show(UserMessage("Boom", actionLabel = "Go") { error("plugin bug") })

        center.performAction(center.messages.value.single().id)

        assertTrue(center.messages.value.isEmpty(), "the message is still dismissed")
    }

    @Test
    fun `performing an action for a message that is already gone does nothing`() {
        val center = UserMessageCenter()
        center.info("gone")
        val id = center.messages.value.single().id
        center.dismiss(id)

        center.performAction(id)

        assertTrue(center.messages.value.isEmpty())
    }

    /**
     * A plugin's `register` runs before any project is open, so its first message is always posted with
     * nobody listening. Losing those is losing the plugin's one chance to say something at load, which is
     * exactly when a toolchain plugin has something to say.
     */
    @Test
    fun `messages posted before anyone was listening are delivered on subscribe`() {
        val center = UserMessageCenter()
        center.info("posted at startup")
        center.error("and this one")

        val seen = mutableListOf<String>()
        center.onMessage { seen += it.message.text }

        assertEquals(listOf("posted at startup", "and this one"), seen)
    }

    @Test
    fun `a second subscriber replays nothing, so a project switch does not duplicate messages`() {
        val center = UserMessageCenter()
        center.info("posted at startup")

        val first = mutableListOf<String>()
        val firstSub = center.onMessage { first += it.message.text }
        firstSub.dispose()

        val second = mutableListOf<String>()
        center.onMessage { second += it.message.text }

        assertEquals(listOf("posted at startup"), first)
        assertTrue(second.isEmpty(), "the message was already delivered once; a new backend must not re-post it")
    }

    @Test
    fun `a message dismissed before anyone subscribed is not delivered later`() {
        val center = UserMessageCenter()
        center.info("transient")
        center.dismissAll()

        val seen = mutableListOf<String>()
        center.onMessage { seen += it.message.text }

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a subscriber that throws does not stop the post or the messages after it`() {
        val center = UserMessageCenter()
        val seen = mutableListOf<String>()
        center.onMessage { error("listener bug") }
        center.onMessage { seen += it.message.text }

        center.info("first")
        center.info("second")

        assertEquals(listOf("first", "second"), seen)
        assertEquals(2, center.messages.value.size, "the queue is unaffected by a listener failing")
    }

    @Test
    fun `progress appears when started and goes away when finished`() {
        val center = UserMessageCenter()
        val handle = center.startProgress("Unpacking the NDK toolchain")

        val running = center.progress.value.single()
        assertEquals("Unpacking the NDK toolchain", running.title)
        assertNull(running.detail)
        assertNull(running.fraction, "unknown progress runs indeterminate rather than sitting at zero")

        handle.detail = "sysroot/usr/include"
        handle.fraction = 0.5f
        val updated = center.progress.value.single()
        assertEquals("sysroot/usr/include", updated.detail)
        assertEquals(0.5f, updated.fraction)

        handle.finish()
        assertTrue(center.progress.value.isEmpty())
    }

    @Test
    fun `finishing twice is harmless and does not disturb other work`() {
        val center = UserMessageCenter()
        val first = center.startProgress("first")
        val second = center.startProgress("second")

        first.finish()
        first.finish()

        assertEquals(listOf("second"), center.progress.value.map { it.title })
        second.finish()
        assertTrue(center.progress.value.isEmpty())
    }

    /** A plugin computing `done / total` gets this wrong; the bar must not be asked to draw it. */
    @Test
    fun `an out-of-range or NaN fraction is clamped rather than shown`() {
        val center = UserMessageCenter()
        val handle = center.startProgress("work")

        handle.fraction = 4f
        assertEquals(1f, center.progress.value.single().fraction)

        handle.fraction = -1f
        assertEquals(0f, center.progress.value.single().fraction)

        handle.fraction = Float.NaN
        assertNull(center.progress.value.single().fraction, "NaN is not-known, which is indeterminate")
    }

    @Test
    fun `updating a finished handle cannot resurrect its row`() {
        val center = UserMessageCenter()
        val handle = center.startProgress("work")
        handle.finish()

        handle.detail = "still going"
        handle.fraction = 0.9f

        assertTrue(center.progress.value.isEmpty())
    }

    @Test
    fun `dismissAll clears the queue and leaves running work alone`() {
        val center = UserMessageCenter()
        center.info("a")
        center.info("b")
        val handle = center.startProgress("work")

        center.dismissAll()

        assertTrue(center.messages.value.isEmpty())
        assertEquals(1, center.progress.value.size, "clearing messages is not cancelling work")
        handle.finish()
    }

    @Test
    fun `the posted message is the plugin's own object, so its action is not copied away`() {
        val center = UserMessageCenter()
        val message = UserMessage("x")
        center.show(message)

        assertSame(message, center.messages.value.single().message)
    }
}
