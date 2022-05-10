package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.Hasher;
import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;

import java.nio.charset.StandardCharsets;

public class AttributeDefinitionSnapshot extends AbstractIsolatableScalarValue<Attribute<?>> {

    private final ClassLoaderHierarchyHasher classLoaderHasher;

    public AttributeDefinitionSnapshot(Attribute<?> value, ClassLoaderHierarchyHasher classLoaderHasher) {
        super(value);
        this.classLoaderHasher = classLoaderHasher;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(getValue().getName(), StandardCharsets.UTF_8);
        Class<?> type = getValue().getType();
        ImplementationSnapshot.of(type, classLoaderHasher).appendToHasher(hasher);
    }
}