package org.gradle.configuration.internal;

public class UserCodeApplicationId {

    private final long id;

    UserCodeApplicationId(long id) {
        this.id = id;
    }

    public long longValue() {
        return id;
    }

}
