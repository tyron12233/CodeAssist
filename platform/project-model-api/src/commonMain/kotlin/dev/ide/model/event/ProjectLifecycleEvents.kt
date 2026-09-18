package dev.ide.model.event

import dev.ide.platform.Topic
import dev.ide.platform.deliverToAll

/**
 * A project became the active engine, or the previously-active one was closed. [root] is the workspace root.
 *
 * Switching projects publishes [Opened] for the incoming project and then [Closed] for the outgoing one,
 * which is the reverse of the intuitive order: [Closed] is published while the outgoing engine is still
 * alive, before it is disposed. The first project opened in a session produces [Opened] alone.
 */
sealed interface ProjectEvent {
    data class Opened(val root: String) : ProjectEvent
    data class Closed(val root: String) : ProjectEvent
}

fun interface ProjectEventListener {
    fun onProjectEvent(event: ProjectEvent)
}

/**
 * Which project the IDE is working on, on the application message bus.
 *
 * A plugin subscribes through its registrar (`reg.busConnection().subscribe(ProjectTopics.LIFECYCLE,
 * listener)`); that connection is tracked, so the subscription is removed when the plugin unloads.
 *
 * This is the coarser companion to [ProjectModelTopics.CHANGES]. That one reports commits to the model of
 * the open project (modules, dependencies, source sets); this one reports the open project itself changing,
 * which is when a plugin holding anything derived from a project has to drop it.
 *
 * A plugin's `register` runs once, at startup, before any project is open, so this is also how a plugin
 * that needs an open project learns it has one. The IDE is the sole publisher and the publish is guarded:
 * a listener that throws cannot break the switch — every listener is notified and the first failure is
 * rethrown afterwards. Delivery is synchronous, in subscription order, on the thread performing the switch.
 */
object ProjectTopics {
    val LIFECYCLE: Topic<ProjectEventListener> = Topic("ide.project") { listeners ->
        ProjectEventListener { event -> deliverToAll(listeners) { it.onProjectEvent(event) } }
    }
}
