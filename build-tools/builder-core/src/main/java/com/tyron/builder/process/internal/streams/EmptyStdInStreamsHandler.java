package com.tyron.builder.process.internal.streams;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.process.internal.StreamsHandler;

import java.io.IOException;
import java.util.concurrent.Executor;

/**
 * A handler that writes nothing to the process' stdin
 */
public class EmptyStdInStreamsHandler implements StreamsHandler {
    @Override
    public void connectStreams(Process process, String processName, Executor executor) {
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void disconnect() {
    }
}
