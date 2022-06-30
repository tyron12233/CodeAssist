package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.internal.process.ArgCollector;

public interface CompileSpecToArguments<T> {
    void collectArguments(T spec, ArgCollector collector);
}
