package dev.ide.core.notify

import dev.ide.platform.Disposable
import dev.ide.platform.notify.ProgressHandle
import dev.ide.platform.notify.UserMessage
import dev.ide.platform.notify.UserMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The host's [UserMessages]: what a plugin says to the user, held as observable state for the shell to
 * render.
 *
 * This is the whole engine side of the capability. It is deliberately a plain state holder with no Compose
 * and no coroutine of its own, so every rule it enforces (what queues, what replaces what, what survives a
 * dismiss) is testable without a UI, which is the half that usually goes wrong.
 *
 * Thread-safe: a plugin calls in from wherever its work runs, which is a build task's IO dispatcher as often
 * as anything else.
 */
class UserMessageCenter : UserMessages {

    /**
     * The queue, oldest first. The shell shows the head and calls [dismiss] when it goes away, rather than
     * the center timing anything itself: how long a snackbar stays is the UI's business, and a center that
     * expired messages on a timer would race the shell over which message is on screen.
     */
    private val _messages = MutableStateFlow<List<Posted>>(emptyList())
    val messages: StateFlow<List<Posted>> = _messages.asStateFlow()

    /** Long work currently on screen, in the order it started. */
    private val _progress = MutableStateFlow<List<Running>>(emptyList())
    val progress: StateFlow<List<Running>> = _progress.asStateFlow()

    private val ids = AtomicLong()

    /** A posted [UserMessage] plus the id the shell dismisses it by. */
    class Posted(val id: Long, val message: UserMessage)

    /** One [startProgress] call as the shell sees it. */
    class Running(val id: Long, val title: String, val detail: String?, val fraction: Float?)

    /**
     * Told about each message as it is posted, so a surface that is not a snackbar can also carry it.
     *
     * The queue above is the transient surface; this is how a message ALSO reaches somewhere durable (the
     * notification center), which matters because the thing a plugin has to say is usually about work the
     * user was not watching. A listener that throws is dropped from the notification, never from the post.
     */
    private val listeners = CopyOnWriteArrayList<(Posted) -> Unit>()

    /**
     * The highest message id already handed to a listener.
     *
     * A plugin's `register` runs at startup, before any project is open, and the durable surface a listener
     * writes into belongs to an open project. So the first messages are always posted with nobody listening,
     * and without this they were silently lost: the queue held them, the notification center never saw them,
     * and the plugin's one chance to say something at load went nowhere. Delivery is keyed on this rather
     * than on the queue, which is capped and is emptied by the user dismissing things.
     */
    private var deliveredThrough = 0L

    private val deliveryLock = Any()

    /**
     * Subscribe to posts, starting with any that were posted before there was anyone to hear them.
     *
     * A listener is called on whatever thread posted. Each message is delivered once across every listener
     * this center ever has, so a second subscriber (the next project's backend) replays nothing.
     */
    fun onMessage(listener: (Posted) -> Unit): Disposable {
        synchronized(deliveryLock) {
            listeners.add(listener)
            deliver(_messages.value.filter { it.id > deliveredThrough })
        }
        return Disposable { listeners.remove(listener) }
    }

    /** Hand [posted] to every listener and advance the watermark. Callers hold [deliveryLock]. */
    private fun deliver(posted: List<Posted>) {
        if (posted.isEmpty() || listeners.isEmpty()) return
        for (one in posted) {
            // A listener is host code, but it writes into a file-backed store; a throw there must cost that
            // one delivery, never the post.
            listeners.forEach { runCatching { it(one) } }
            deliveredThrough = maxOf(deliveredThrough, one.id)
        }
    }

    override fun show(message: UserMessage) {
        val posted = Posted(ids.incrementAndGet(), message)
        synchronized(deliveryLock) { deliver(listOf(posted)) }
        _messages.update { current ->
            // A plugin in a loop (a failing watcher, a per-file warning) must not be able to push the rest of
            // the queue off the screen or grow it without bound. Dropping the OLDEST keeps the most recent
            // message, which is the one that describes the state the user is in now.
            (current + posted).let { if (it.size > MAX_QUEUED) it.drop(it.size - MAX_QUEUED) else it }
        }
    }

    /** Take the message with [id] off the queue. Called by the shell once it has been shown and gone away. */
    fun dismiss(id: Long) {
        _messages.update { current -> current.filterNot { it.id == id } }
    }

    /** Drop every queued message. The shell calls this when the user clears them all at once. */
    fun dismissAll() {
        _messages.value = emptyList()
    }

    /** Run the action attached to message [id], if it has one, and take the message off the queue. */
    fun performAction(id: Long) {
        val posted = _messages.value.firstOrNull { it.id == id } ?: return
        dismiss(id)
        // A plugin's own code: a throw here must cost the message, never the shell that rendered it.
        runCatching { posted.message.onAction?.invoke() }
    }

    override fun startProgress(title: String): ProgressHandle {
        val id = ids.incrementAndGet()
        _progress.update { it + Running(id, title, detail = null, fraction = null) }
        return Handle(id, title)
    }

    private inner class Handle(private val id: Long, private val title: String) : ProgressHandle {
        @Volatile
        private var finished = false

        override var detail: String? = null
            set(value) {
                field = value
                republish()
            }

        override var fraction: Float? = null
            // Clamped rather than trusted: a plugin computing `done / total` with an off-by-one would
            // otherwise hand the bar a value outside its range, and NaN would leave it blank.
            set(value) {
                field = value?.takeIf { !it.isNaN() }?.coerceIn(0f, 1f)
                republish()
            }

        private fun republish() {
            if (finished) return
            _progress.update { current ->
                current.map { if (it.id == id) Running(id, title, detail, fraction) else it }
            }
        }

        override fun finish() {
            if (finished) return
            finished = true
            _progress.update { current -> current.filterNot { it.id == id } }
        }
    }

    private companion object {
        /**
         * How many messages may be waiting at once. Small on purpose: this is a transient surface, and a
         * plugin with more than a handful of things to say should be writing to the log or the build console,
         * which are built to be scrolled.
         */
        const val MAX_QUEUED = 8
    }
}
