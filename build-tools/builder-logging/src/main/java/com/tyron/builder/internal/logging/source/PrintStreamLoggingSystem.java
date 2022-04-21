package com.tyron.builder.internal.logging.source;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.logging.events.StyledTextOutputEvent;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.time.Clock;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.StandardOutputListener;
import com.tyron.builder.internal.io.LinePerThreadBufferingOutputStream;
import com.tyron.builder.internal.io.TextStream;
import com.tyron.builder.internal.logging.config.LoggingSourceSystem;
import com.tyron.builder.internal.logging.events.LogLevelChangeEvent;

import javax.annotation.Nullable;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link LoggingSourceSystem} which routes content written to a {@code PrintStream} to a {@link OutputEventListener}.
 * Generates a {@link StyledTextOutputEvent} instance when a line of text is written to the {@code PrintStream}.
 * Generates a {@link LogLevelChangeEvent} when the log level for this {@code LoggingSystem} is changed.
 */
abstract class PrintStreamLoggingSystem implements LoggingSourceSystem {
    private final AtomicReference<StandardOutputListener> destination = new AtomicReference<StandardOutputListener>();
    private final PrintStream outstr = new LinePerThreadBufferingOutputStream(new TextStream() {
        @Override
        public void text(String output) {
            destination.get().onOutput(output);
        }

        @Override
        public void endOfStream(@Nullable Throwable failure) {
        }
    });
    private PrintStreamDestination original;
    private boolean enabled;
    private LogLevel logLevel;
    private final StandardOutputListener listener;
    private final OutputEventListener outputEventListener;

    protected PrintStreamLoggingSystem(OutputEventListener listener, String category, Clock clock) {
        outputEventListener = listener;
        this.listener = new OutputEventDestination(listener, category, clock);
    }

    /**
     * Returns the current value of the PrintStream
     */
    protected abstract PrintStream get();

    /**
     * Sets the current value of the PrintStream
     */
    protected abstract void set(PrintStream printStream);

    @Override
    public Snapshot snapshot() {
        return new SnapshotImpl(enabled, logLevel);
    }

    @Override
    public void restore(Snapshot state) {
        SnapshotImpl snapshot = (SnapshotImpl) state;
        enabled = snapshot.enabled;
        logLevel = snapshot.logLevel;
        if (enabled) {
            install();
        } else {
            uninstall();
        }
    }

    @Override
    public Snapshot setLevel(LogLevel logLevel) {
        Snapshot snapshot = snapshot();
        if (logLevel != this.logLevel) {
            this.logLevel = logLevel;
            if (enabled) {
                outstr.flush();
                outputEventListener.onOutput(new LogLevelChangeEvent(logLevel));
            }
        }
        return snapshot;
    }

    @Override
    public Snapshot startCapture() {
        Snapshot snapshot = snapshot();
        if (!enabled) {
            install();
        }
        return snapshot;
    }

    private void uninstall() {
        if (original != null) {
            outstr.flush();
            destination.set(original);
            set(original.originalStream);
            original = null;
        }
    }

    private void install() {
        if (original == null) {
            PrintStream originalStream = get();
            original = new PrintStreamDestination(originalStream);
        }
        enabled = true;
        outstr.flush();
        outputEventListener.onOutput(new LogLevelChangeEvent(logLevel));
        destination.set(listener);
        if (get() != outstr) {
            set(outstr);
        }
    }

    private static class PrintStreamDestination implements StandardOutputListener {
        private final PrintStream originalStream;

        public PrintStreamDestination(PrintStream originalStream) {
            this.originalStream = originalStream;
        }

        @Override
        public void onOutput(CharSequence output) {
            originalStream.print(output);
        }
    }

    private static class SnapshotImpl implements Snapshot {
        private final boolean enabled;
        private final LogLevel logLevel;

        public SnapshotImpl(boolean enabled, LogLevel logLevel) {
            this.enabled = enabled;
            this.logLevel = logLevel;
        }
    }

    private static class OutputEventDestination implements StandardOutputListener {
        private final OutputEventListener listener;
        private final String category;
        private final Clock clock;

        public OutputEventDestination(OutputEventListener listener, String category, Clock clock) {
            this.listener = listener;
            this.category = category;
            this.clock = clock;
        }

        @Override
        public void onOutput(CharSequence output) {
            OperationIdentifier buildOperationId = CurrentBuildOperationRef.instance().getId();
            StyledTextOutputEvent event = new StyledTextOutputEvent(clock.getCurrentTime(), category, null, buildOperationId, output.toString());
            listener.onOutput(event);
        }
    }
}
