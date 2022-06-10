package org.gradle.internal.process;

public interface ArgCollector {
    
    ArgCollector args(Object... args);

    ArgCollector args(Iterable<?> args);

}
