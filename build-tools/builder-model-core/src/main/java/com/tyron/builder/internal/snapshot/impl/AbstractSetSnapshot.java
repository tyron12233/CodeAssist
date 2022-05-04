package com.tyron.builder.internal.snapshot.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.hash.Hashable;

import java.nio.charset.StandardCharsets;

class AbstractSetSnapshot<T extends Hashable> implements Hashable {
    protected final ImmutableSet<T> elements;

    public AbstractSetSnapshot(ImmutableSet<T> elements) {
        this.elements = elements;
    }

    public ImmutableSet<T> getElements() {
        return elements;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString("Set", StandardCharsets.UTF_8);
        hasher.putInt(elements.size());
        for (T element : elements) {
            element.appendToHasher(hasher);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        AbstractSetSnapshot other = (AbstractSetSnapshot) obj;
        return elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }
}