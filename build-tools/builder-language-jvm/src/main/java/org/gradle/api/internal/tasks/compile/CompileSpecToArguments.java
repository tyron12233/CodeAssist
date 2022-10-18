package org.gradle.api.internal.tasks.compile;

import org.gradle.internal.process.ArgCollector;

public interface CompileSpecToArguments<T> {
    void collectArguments(T spec, ArgCollector collector);
}
