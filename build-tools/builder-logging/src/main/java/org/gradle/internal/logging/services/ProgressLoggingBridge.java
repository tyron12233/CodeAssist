package org.gradle.internal.logging.services;

import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.logging.events.ProgressCompleteEvent;
import org.gradle.internal.logging.events.ProgressEvent;
import org.gradle.internal.logging.events.ProgressStartEvent;
import org.gradle.internal.logging.progress.ProgressListener;

public class ProgressLoggingBridge implements ProgressListener {
    private final OutputEventListener listener;

    public ProgressLoggingBridge(OutputEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void completed(ProgressCompleteEvent event) {
        listener.onOutput(event);
    }

    @Override
    public void started(ProgressStartEvent event) {
        listener.onOutput(event);
    }

    @Override
    public void progress(ProgressEvent event) {
        listener.onOutput(event);
    }
}