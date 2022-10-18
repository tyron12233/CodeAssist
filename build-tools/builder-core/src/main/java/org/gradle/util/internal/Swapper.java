package org.gradle.util.internal;

import org.gradle.api.Action;
import org.gradle.internal.Factory;

/**
 * Generic utility for temporarily changing something.
 */
public class Swapper<T> {

    private final Factory<? extends T> getter;
    private final Action<? super T> setter;

    public Swapper(Factory<? extends T> getter, Action<? super T> setter) {
        this.getter = getter;
        this.setter = setter;
    }

    public <Y extends T, N> N swap(Y value, Factory<N> whileSwapped) {
        T originalValue = getter.create();
        setter.execute(value);
        try {
            return whileSwapped.create();
        } finally {
            setter.execute(originalValue);
        }
    }
}
