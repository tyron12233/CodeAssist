package com.tyron.builder.api.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import org.jetbrains.annotations.Nullable;

public class UnknownClassloaderImplementationSnapshot extends ImplementationSnapshot {

    public UnknownClassloaderImplementationSnapshot(String typeName) {
        super(typeName);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        throw new RuntimeException("Cannot hash implementation of class " + getTypeName() + " loaded by an unknown classloader");
    }

    @Override
    protected boolean isSameSnapshot(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        UnknownClassloaderImplementationSnapshot that = (UnknownClassloaderImplementationSnapshot) o;

        return getTypeName().equals(that.getTypeName());
    }

    @Override
    public HashCode getClassLoaderHash() {
        return null;
    }

    @Override
    public boolean isUnknown() {
        return true;
    }

    @Override
    @Nullable
    public UnknownReason getUnknownReason() {
        return UnknownReason.UNKNOWN_CLASSLOADER;
    }

    @Override
    public boolean equals(Object o) {
        return false;
    }

    @Override
    public int hashCode() {
        return getTypeName().hashCode();
    }

    @Override
    public String toString() {
        return getTypeName() + "@" + "<Unknown classloader>";
    }
}