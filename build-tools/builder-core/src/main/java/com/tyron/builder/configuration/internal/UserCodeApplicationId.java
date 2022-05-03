package com.tyron.builder.configuration.internal;

public class UserCodeApplicationId {

    private final long id;

    UserCodeApplicationId(long id) {
        this.id = id;
    }

    public long longValue() {
        return id;
    }

}
