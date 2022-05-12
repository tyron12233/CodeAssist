package com.tyron.builder.internal.snapshot.impl;


import com.google.common.hash.Hasher;
import com.tyron.builder.internal.hash.Hashable;

class AbstractManagedValueSnapshot<T extends Hashable> implements Hashable {
    protected final T state;

    public AbstractManagedValueSnapshot(T state) {
        this.state = state;
    }

    public T getState() {
        return state;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        AbstractManagedValueSnapshot other = (AbstractManagedValueSnapshot) obj;
        return state.equals(other.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        state.appendToHasher(hasher);
    }
}