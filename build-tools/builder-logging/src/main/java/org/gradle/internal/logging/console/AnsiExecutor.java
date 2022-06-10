package org.gradle.internal.logging.console;

import org.gradle.api.Action;

public interface AnsiExecutor {
    void write(Action<? super AnsiContext> action);
    void writeAt(Cursor writePos, Action<? super AnsiContext> action);
}
