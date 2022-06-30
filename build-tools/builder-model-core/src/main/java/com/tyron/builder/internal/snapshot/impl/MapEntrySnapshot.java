package com.tyron.builder.internal.snapshot.impl;

public class MapEntrySnapshot<T> {
    private final T key;
    private final T value;

    public MapEntrySnapshot(T key, T value) {
        this.key = key;
        this.value = value;
    }

    public T getKey() {
        return key;
    }

    public T getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        MapEntrySnapshot<?> that = (MapEntrySnapshot<?>) o;

        if (!key.equals(that.key)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + value.hashCode();
        return result;
    }
}