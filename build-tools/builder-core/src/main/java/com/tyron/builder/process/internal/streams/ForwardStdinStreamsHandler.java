package com.tyron.builder.process.internal.streams;

import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.process.internal.StreamsHandler;
import com.tyron.builder.util.internal.DisconnectableInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * Forwards the contents of an {@link InputStream} to the process' stdin
 */
public class ForwardStdinStreamsHandler implements StreamsHandler {
    private final InputStream input;
    private final CountDownLatch completed = new CountDownLatch(1);
    private Executor executor;
    private ExecOutputHandleRunner standardInputWriter;

    public ForwardStdinStreamsHandler(InputStream input) {
        this.input = input;
    }

    @Override
    public void connectStreams(Process process, String processName, Executor executor) {
        this.executor = executor;

        /*
            There's a potential problem here in that DisconnectableInputStream reads from input in the background.
            This won't automatically stop when the process is over. Therefore, if input is not closed then this thread
            will run forever. It would be better to ensure that this thread stops when the process does.
         */
        InputStream instr = new DisconnectableInputStream(input);
        standardInputWriter = new ExecOutputHandleRunner("write standard input to " + processName, instr, process.getOutputStream(), completed);
    }

    @Override
    public void start() {
        executor.execute(standardInputWriter);
    }

    @Override
    public void stop() {
        disconnect();
        try {
            completed.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void disconnect() {
        try {
            standardInputWriter.closeInput();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
