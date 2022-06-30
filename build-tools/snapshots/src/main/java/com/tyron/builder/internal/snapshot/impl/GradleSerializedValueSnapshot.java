package com.tyron.builder.internal.snapshot.impl;

import com.google.common.base.Objects;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshotter;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * An immutable snapshot of the state of some value.
 */
public class GradleSerializedValueSnapshot implements ValueSnapshot {

    private final HashCode implementationHash;
    private final byte[] serializedValue;

    public GradleSerializedValueSnapshot(
            @Nullable HashCode implementationHash,
            byte[] serializedValue
    ) {
        this.implementationHash = implementationHash;
        this.serializedValue = serializedValue;
    }

    @Nullable
    public HashCode getImplementationHash() {
        return implementationHash;
    }

    public byte[] getValue() {
        return serializedValue;
    }

    @Override
    public ValueSnapshot snapshot(Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot snapshot = snapshotter.snapshot(value);
        if (hasSameSerializedValue(value, snapshot)) {
            return this;
        }
        return snapshot;
    }

    private boolean hasSameSerializedValue(Object value, ValueSnapshot snapshot) {
        if (snapshot instanceof GradleSerializedValueSnapshot) {
            GradleSerializedValueSnapshot newSnapshot = (GradleSerializedValueSnapshot) snapshot;
            if (!Objects.equal(implementationHash, newSnapshot.implementationHash)) {
                // Different implementation - assume value has changed
                return false;
            }
            if (Arrays.equals(serializedValue, newSnapshot.serializedValue)) {
                // Same serialized content - value has not changed
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        if (implementationHash == null) {
            hasher.putString("null", StandardCharsets.UTF_8);
        } else {
            hasher.putBytes(implementationHash.asBytes());
        }
        hasher.putBytes(serializedValue);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        GradleSerializedValueSnapshot other = (GradleSerializedValueSnapshot) obj;
        return Objects.equal(implementationHash, other.implementationHash) && Arrays.equals(serializedValue, other.serializedValue);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(serializedValue);
    }
}
