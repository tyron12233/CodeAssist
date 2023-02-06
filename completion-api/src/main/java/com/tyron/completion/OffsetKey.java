package com.tyron.completion;

public final class OffsetKey {
    private final String myName; // for debug purposes only
    private final boolean myMovableToRight;

    private OffsetKey(String name, final boolean movableToRight) {
        myName = name;
        myMovableToRight = movableToRight;
    }

    public String toString() {
        return myName;
    }

    public boolean isMovableToRight() {
        return myMovableToRight;
    }

    public static OffsetKey create(String name) {
        return create(name, true);
    }

    public static OffsetKey create(String name, final boolean movableToRight) {
        return new OffsetKey(name, movableToRight);
    }
}