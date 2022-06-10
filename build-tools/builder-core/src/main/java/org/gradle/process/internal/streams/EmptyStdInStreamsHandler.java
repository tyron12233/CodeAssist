package org.gradle.process.internal.streams;

import org.gradle.api.UncheckedIOException;
import org.gradle.process.internal.StreamsHandler;

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
