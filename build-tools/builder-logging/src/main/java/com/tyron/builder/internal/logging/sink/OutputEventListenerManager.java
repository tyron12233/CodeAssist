package com.tyron.builder.internal.logging.sink;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;

/**
 * Manages dispatch of output events to the renderer and at most one other arbitrary listener.
 *
 * This is a degeneralisation of the standard ListenerManager pattern as a performance optimisation.
 * Builds generate many output events, and an extra layer of generic listener manager overhead,
 * on top of OutputEventRenderer noticeably degraded performance.
 */
public class OutputEventListenerManager {
    private final OutputEventListener renderer;
    private OutputEventListener other;

    private final OutputEventListener broadcaster = new OutputEventListener() {
        @Override
        public void onOutput(OutputEvent event) {
            renderer.onOutput(event);

            OutputEventListener otherRef = other;
            if (otherRef != null) {
                otherRef.onOutput(event);
            }
        }
    };

    public OutputEventListenerManager(OutputEventListener renderer) {
        this.renderer = renderer;
    }

    public void setListener(OutputEventListener listener) {
        other = listener;
    }

    public void removeListener(OutputEventListener listener) {
        if (other == listener) {
            other = null;
        }
    }

    public OutputEventListener getBroadcaster() {
        return broadcaster;
    }

}
