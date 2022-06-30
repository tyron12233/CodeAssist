package com.tyron.builder.internal.logging.console;

import com.tyron.builder.internal.logging.events.OutputEvent;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.internal.logging.events.EndOutputEvent;
import com.tyron.builder.internal.logging.events.FlushOutputEvent;
import com.tyron.builder.internal.logging.events.UpdateNowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Queue output events to be forwarded and schedule flush when time passed or if end of build is signalled.
 */
public class ThrottlingOutputEventListener implements OutputEventListener {
    private final OutputEventListener listener;

    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final int throttleMs;
    private final Object lock = new Object();

    private final List<OutputEvent> queue = new ArrayList<OutputEvent>();

    public ThrottlingOutputEventListener(OutputEventListener listener, Clock clock) {
        this(listener, Integer.getInteger("com.tyron.builder.internal.console.throttle", 100), Executors.newSingleThreadScheduledExecutor(), clock);
    }

    ThrottlingOutputEventListener(OutputEventListener listener, int throttleMs, ScheduledExecutorService executor, Clock clock) {
        this.throttleMs = throttleMs;
        this.listener = listener;
        this.executor = executor;
        this.clock = clock;
        scheduleUpdateNow();
    }

    private void scheduleUpdateNow() {
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                onOutput(new UpdateNowEvent(clock.getCurrentTime()));
            }
        }, throttleMs, throttleMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onOutput(OutputEvent newEvent) {
        synchronized (lock) {
            queue.add(newEvent);

            if (queue.size() == 10000 || newEvent instanceof UpdateNowEvent) {
                renderNow();
                return;
            }

            if (newEvent instanceof FlushOutputEvent) {
                renderNow();
                return;
            }

            if (newEvent instanceof EndOutputEvent) {
                // Flush and clean up
                renderNow();
                executor.shutdown();
            }

            // Else, wait for the next update event
        }
    }

    private void renderNow() {
        // Remove event only as it is handled, and leave unhandled events in the queue
        while (!queue.isEmpty()) {
            OutputEvent event = queue.remove(0);
            listener.onOutput(event);
        }
    }
}
