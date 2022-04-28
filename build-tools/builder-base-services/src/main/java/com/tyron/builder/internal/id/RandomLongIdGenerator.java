package com.tyron.builder.internal.id;

import java.util.Random;

public class RandomLongIdGenerator implements IdGenerator<Long> {
    private final Random random = new Random();

    @Override
    public Long generateId() {
        return random.nextLong();
    }
}