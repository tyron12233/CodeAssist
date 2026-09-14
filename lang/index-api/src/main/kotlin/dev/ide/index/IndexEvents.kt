package dev.ide.index

import dev.ide.platform.Topic

/**
 * Index build progress, on the application message bus.
 *
 * A plugin subscribes through its registrar (`reg.busConnection().subscribe(IndexTopics.INDEXING,
 * listener)`); that connection is tracked, so the subscription is removed when the plugin unloads. One
 * [IndexEvent.Started] and one [IndexEvent.Finished] per build, not one per progress tick: a plugin that
 * needs the fraction and the message reads [IndexService]'s observable status instead.
 *
 * This is the coarse form of the gate index-backed features already honour. Until a build finishes with
 * [IndexStatus.ready], a classpath or library lookup answers nothing rather than falling back to a live
 * scan, so a plugin that queries the index off its own trigger should wait for a ready [IndexEvent.Finished]
 * before treating an empty result as an absent symbol.
 *
 * The IDE is the sole publisher and the publish is guarded: a listener that throws cannot break indexing.
 * Delivery is synchronous, in subscription order, on the thread that drove the transition, which is an
 * indexing worker rather than the UI thread.
 */
object IndexTopics {
    val INDEXING: Topic<IndexEventListener> = Topic("ide.indexing", IndexEventListener::class.java)
}

/** Indexing progress. [Finished] carries the terminal [IndexStatus], whose `ready` flag distinguishes a
 *  successful build from a failed one. */
sealed interface IndexEvent {
    data object Started : IndexEvent
    data class Finished(val status: IndexStatus) : IndexEvent
}

fun interface IndexEventListener {
    fun onIndexEvent(event: IndexEvent)
}
