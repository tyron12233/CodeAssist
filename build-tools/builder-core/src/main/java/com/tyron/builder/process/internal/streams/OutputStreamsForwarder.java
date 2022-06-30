package com.tyron.builder.process.internal.streams;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.operations.CurrentBuildOperationPreservingRunnable;
import com.tyron.builder.process.internal.StreamsHandler;

import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * Reads from the process' stdout and stderr (if not merged into stdout) and forwards to {@link OutputStream}.
 */
public class OutputStreamsForwarder implements StreamsHandler {
    private final OutputStream standardOutput;
    private final OutputStream errorOutput;
    private final boolean readErrorStream;
    private final CountDownLatch completed;
    private Executor executor;
    private ExecOutputHandleRunner standardOutputReader;
    private ExecOutputHandleRunner standardErrorReader;

    public OutputStreamsForwarder(OutputStream standardOutput, OutputStream errorOutput, boolean readErrorStream) {
        this.standardOutput = standardOutput;
        this.errorOutput = errorOutput;
        this.readErrorStream = readErrorStream;
        this.completed = new CountDownLatch(readErrorStream ? 2 : 1);
    }

    @Override
    public void connectStreams(Process process, String processName, Executor executor) {
        this.executor = executor;
        standardOutputReader = new ExecOutputHandleRunner("read standard output of " + processName, process.getInputStream(), standardOutput, completed);
        if (readErrorStream) {
            standardErrorReader = new ExecOutputHandleRunner("read error output of " + processName, process.getErrorStream(), errorOutput, completed);
        }
    }

    @Override
    public void start() {
        if (readErrorStream) {
            executor.execute(wrapInBuildOperation(standardErrorReader));
        }
        executor.execute(wrapInBuildOperation(standardOutputReader));
    }

    private Runnable wrapInBuildOperation(Runnable runnable) {
        return new CurrentBuildOperationPreservingRunnable(runnable);
    }

    @Override
    public void stop() {
        try {
            completed.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public void disconnect() {
        standardOutputReader.disconnect();
        if (readErrorStream) {
            standardErrorReader.disconnect();
        }
    }
}
