package com.tyron.builder.internal.logging.services;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.ProgressCompleteEvent;
import com.tyron.builder.internal.logging.events.ProgressEvent;
import com.tyron.builder.internal.logging.events.ProgressStartEvent;
import com.tyron.builder.internal.logging.progress.ProgressListener;

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