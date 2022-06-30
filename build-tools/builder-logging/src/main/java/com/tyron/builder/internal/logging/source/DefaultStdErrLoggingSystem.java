package com.tyron.builder.internal.logging.source;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.time.Clock;

import java.io.PrintStream;

public class DefaultStdErrLoggingSystem extends PrintStreamLoggingSystem implements StdErrLoggingSystem {

    public DefaultStdErrLoggingSystem(OutputEventListener listener, Clock clock) {
        super(listener, "system.err", clock);
    }

    @Override
    protected PrintStream get() {
        return System.err;
    }

    @Override
    protected void set(PrintStream printStream) {
        System.setErr(printStream);
    }
}
