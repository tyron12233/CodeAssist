package org.gradle.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import org.gradle.internal.hash.Hashes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class ClassImplementationSnapshot extends ImplementationSnapshot {
    private final HashCode classLoaderHash;

    public ClassImplementationSnapshot(String classIdentifier, HashCode classLoaderHash) {
        super(classIdentifier);
        this.classLoaderHash = classLoaderHash;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(ClassImplementationSnapshot.class.getName(), StandardCharsets.UTF_8);
        hasher.putString(classIdentifier, StandardCharsets.UTF_8);
        Hashes.putHash(hasher, classLoaderHash);
    }

    @Override
    protected boolean isSameSnapshot(@Nullable Object o) {
        return equals(o);
    }

    @Nonnull
    @Override
    public HashCode getClassLoaderHash() {
        return classLoaderHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ClassImplementationSnapshot that = (ClassImplementationSnapshot) o;
        return classIdentifier.equals(that.classIdentifier) &&
            classLoaderHash.equals(that.classLoaderHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classIdentifier, classLoaderHash);
    }

    @Override
    public String toString() {
        return classIdentifier + "@" + classLoaderHash;
    }
}