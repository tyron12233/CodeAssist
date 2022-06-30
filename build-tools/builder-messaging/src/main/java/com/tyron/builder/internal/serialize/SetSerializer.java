package com.tyron.builder.internal.serialize;

import com.google.common.base.Objects;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.Set;

public class SetSerializer<T> extends AbstractCollectionSerializer<T, Set<T>> implements Serializer<Set<T>> {

    private final boolean linkedHashSet;

    public SetSerializer(Serializer<T> entrySerializer) {
        this(entrySerializer, true);
    }

    public SetSerializer(Serializer<T> entrySerializer, boolean linkedHashSet) {
        super(entrySerializer);
        this.linkedHashSet = linkedHashSet;
    }

    @Override
    protected Set<T> createCollection(int size) {
        if (size == 0) {
            return Collections.emptySet();
        }
        return linkedHashSet ? Sets.<T>newLinkedHashSetWithExpectedSize(size) : Sets.<T>newHashSetWithExpectedSize(size);
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        SetSerializer<?> rhs = (SetSerializer<?>) obj;
        return linkedHashSet == rhs.linkedHashSet;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), linkedHashSet);
    }

}

