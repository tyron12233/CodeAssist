package com.tyron.builder.internal.logging.console;

import com.tyron.builder.api.Action;

public interface AnsiExecutor {
    void write(Action<? super AnsiContext> action);
    void writeAt(Cursor writePos, Action<? super AnsiContext> action);
}
