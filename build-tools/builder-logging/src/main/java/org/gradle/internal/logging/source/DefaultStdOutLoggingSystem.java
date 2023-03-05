package org.gradle.internal.logging.source;

import org.gradle.internal.logging.events.OutputEventListener;
import org.gradle.internal.time.Clock;

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
