package dev.ide.analysis

import dev.ide.platform.Topic

/**
 * The analysis pipeline's published-diagnostics event, on the application message bus.
 *
 * A plugin subscribes through its registrar (`reg.busConnection().subscribe(AnalysisTopics.ANALYSIS,
 * listener)`); that connection is tracked, so the subscription is removed when the plugin unloads. This
 * carries the same merged stream the editor underlines and the Problems view shows, so a subscriber sees
 * exactly what the user sees. The IDE is the sole publisher and the publish is guarded: a listener that
 * throws cannot break analysis.
 *
 * Delivery is synchronous, in subscription order, on the analysis worker rather than the UI thread, so a
 * listener that touches the UI must marshal. Analysis passes are debounced and re-run per file version, so
 * a file can produce many events while it is being edited.
 */
object AnalysisTopics {
    val ANALYSIS: Topic<AnalysisEventListener> = Topic("ide.analysis", AnalysisEventListener::class.java)
}

/** A file's merged diagnostics were (re)published by the analysis pipeline. [path] is a workspace path. */
data class AnalysisEvent(val path: String, val diagnostics: List<Diagnostic>)

fun interface AnalysisEventListener {
    fun onAnalysisEvent(event: AnalysisEvent)
}
