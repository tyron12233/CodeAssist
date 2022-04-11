package com.tyron.builder.api.internal.tasks.compile;

public interface CommandLineArgumentProvider {
    Iterable<String> asArguments();
}
