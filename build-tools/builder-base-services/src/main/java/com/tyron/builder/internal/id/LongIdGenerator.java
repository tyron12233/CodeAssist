package com.tyron.builder.internal.id;

import java.util.concurrent.atomic.AtomicLong;

public class LongIdGenerator implements IdGenerator<Long> {
    private final AtomicLong nextId = new AtomicLong(1);

    @Override
    public Long generateId() {
        return nextId.getAndIncrement();
    }
}
