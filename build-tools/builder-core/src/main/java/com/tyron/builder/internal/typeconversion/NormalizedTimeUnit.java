package com.tyron.builder.internal.typeconversion;

import java.util.concurrent.TimeUnit;

public class NormalizedTimeUnit {

    private final int value;
    private final TimeUnit timeUnit;

    public NormalizedTimeUnit(int value, TimeUnit timeUnit) {
        this.value = value;
        this.timeUnit = timeUnit;
    }

    public static NormalizedTimeUnit millis(int value) {
        return new NormalizedTimeUnit(value, TimeUnit.MILLISECONDS);
    }

    public int getValue() {
        return value;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }
}
