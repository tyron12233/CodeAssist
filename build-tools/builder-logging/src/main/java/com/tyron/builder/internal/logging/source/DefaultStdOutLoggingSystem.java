package com.tyron.builder.internal.logging.source;

import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.time.Clock;

import java.io.PrintStream;

public class DefaultStdOutLoggingSystem extends PrintStreamLoggingSystem implements StdOutLoggingSystem {

    public DefaultStdOutLoggingSystem(OutputEventListener listener, Clock clock) {
        super(listener, "system.out", clock);
    }

    @Override
    protected PrintStream get() {
        return System.out;
    }

    @Override
    protected void set(PrintStream printStream) {
        System.setOut(printStream);
    }
}
