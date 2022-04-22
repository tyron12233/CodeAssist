package com.tyron.builder.internal.operations;

import java.util.concurrent.atomic.AtomicLong;

public class DefaultBuildOperationIdFactory implements BuildOperationIdFactory {
    public static final long ROOT_BUILD_OPERATION_ID_VALUE = 1L;

    private final AtomicLong nextId = new AtomicLong(ROOT_BUILD_OPERATION_ID_VALUE);

    @Override
    public long nextId() {
        return nextId.getAndIncrement();
    }
}