package com.example.hello

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * State shared by this plugin's two facets, and the whole reason they can be two classes without a bridge.
 *
 * [HelloPlugin] (the engine facet) and [HelloUiPlugin] (the UI facet) are named by the same packaged
 * manifest, so the IDE instantiates both off this APK on ONE classloader. This object is therefore one
 * instance to both of them: the engine facet writes to it when its command runs, the panel reads it, and
 * nothing crosses a process, a serializer or an extension point on the way.
 *
 * It is Compose snapshot state, so a write recomposes the panel with no listener to register and no polling.
 * Snapshot state is safe to write from any thread, which matters because an action runs off the main thread.
 *
 * A *different* plugin could not see this class: each plugin gets its own classloader over its own APK, and
 * the message bus is the channel between them.
 */
object HelloState {

    /** How many times the plugin has said hello, from either facet. */
    var greetings: Int by mutableIntStateOf(0)
        private set

    /** Where the last greeting came from ("the palette", "the panel"), for the panel to show. */
    var lastSource: String? by mutableStateOf(null)
        private set

    fun greeted(source: String) {
        greetings++
        lastSource = source
    }
}
