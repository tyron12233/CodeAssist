package com.tyron.completions;

import org.jetbrains.annotations.NonNls;

/**
 * @author peter
 */
public final class OffsetKey {
    private final String myName; // for debug purposes only
    private final boolean myMovableToRight;

    private OffsetKey(@NonNls String name, final boolean movableToRight) {
        myName = name;
        myMovableToRight = movableToRight;
    }

    public String toString() {
        return myName;
    }

    public boolean isMovableToRight() {
        return myMovableToRight;
    }

    public static OffsetKey create(@NonNls String name) {
        return create(name, true);
    }

    public static OffsetKey create(@NonNls String name, final boolean movableToRight) {
        return new OffsetKey(name, movableToRight);
    }
}
