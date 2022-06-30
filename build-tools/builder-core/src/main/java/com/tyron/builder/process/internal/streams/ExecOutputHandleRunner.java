package com.tyron.builder.process.internal.streams;

import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.internal.concurrent.CompositeStoppable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;

public class ExecOutputHandleRunner implements Runnable {
    private final static Logger LOGGER = Logging.getLogger(ExecOutputHandleRunner.class);

    private final String displayName;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final int bufferSize;
    private final CountDownLatch completed;
    private volatile boolean closed;

    public ExecOutputHandleRunner(String displayName, InputStream inputStream, OutputStream outputStream, CountDownLatch completed) {
        this(displayName, inputStream, outputStream, 8192, completed);
    }

    ExecOutputHandleRunner(String displayName, InputStream inputStream, OutputStream outputStream, int bufferSize, CountDownLatch completed) {
        this.displayName = displayName;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.bufferSize = bufferSize;
        this.completed = completed;
    }

    @Override
    public void run() {
        try {
            forwardContent();
        } finally {
            completed.countDown();
        }
    }

    private void forwardContent() {
        try {
            byte[] buffer = new byte[bufferSize];
            while (!closed) {
                int nread = inputStream.read(buffer);
                if (nread < 0) {
                    break;
                }
                outputStream.write(buffer, 0, nread);
                outputStream.flush();
            }
            CompositeStoppable.stoppable(inputStream, outputStream).stop();
        } catch (Throwable t) {
            if (!closed && !wasInterrupted(t)) {
                LOGGER.error(String.format("Could not %s.", displayName), t);
            }
        }
    }

    /**
     * This can happen e.g. on IBM JDK when a remote process was terminated. Instead of
     * returning -1 on the next read() call, it will interrupt the current read call.
     */
    private boolean wasInterrupted(Throwable t) {
        return t instanceof IOException && "Interrupted system call".equals(t.getMessage());
    }

    public void closeInput() throws IOException {
        disconnect();
        inputStream.close();
    }

    @Override
    public String toString() {
        return displayName;
    }

    public void disconnect() {
        closed = true;
    }
}
