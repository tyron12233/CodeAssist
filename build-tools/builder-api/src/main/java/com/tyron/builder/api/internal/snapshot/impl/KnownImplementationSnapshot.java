package com.tyron.builder.api.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;

public class KnownImplementationSnapshot extends ImplementationSnapshot {
    private final HashCode classLoaderHash;

    public KnownImplementationSnapshot(String typeName, HashCode classLoaderHash) {
        super(typeName);
        this.classLoaderHash = classLoaderHash;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(ImplementationSnapshot.class.getName(), StandardCharsets.UTF_8);
        hasher.putString(getTypeName(), StandardCharsets.UTF_8);
        hasher.putBytes(classLoaderHash.asBytes());
    }

    @Override
    protected boolean isSameSnapshot(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        KnownImplementationSnapshot that = (KnownImplementationSnapshot) o;

        if (!getTypeName().equals(that.getTypeName())) {
            return false;
        }
        return classLoaderHash.equals(that.classLoaderHash);
    }

    @NotNull
    @Override
    public HashCode getClassLoaderHash() {
        return classLoaderHash;
    }

    @Override
    public boolean isUnknown() {
        return false;
    }

    @Override
    @Nullable
    public UnknownReason getUnknownReason() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        KnownImplementationSnapshot that = (KnownImplementationSnapshot) o;
        if (this == o) {
            return true;
        }


        if (!getTypeName().equals(that.getTypeName())) {
            return false;
        }
        return classLoaderHash.equals(that.classLoaderHash);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + classLoaderHash.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return getTypeName() + "@" + classLoaderHash;
    }
}