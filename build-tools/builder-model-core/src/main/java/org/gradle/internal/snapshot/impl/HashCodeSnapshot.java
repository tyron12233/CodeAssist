package org.gradle.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import org.gradle.internal.hash.Hashes;

public class HashCodeSnapshot extends AbstractIsolatableScalarValue<HashCode> {
    public HashCodeSnapshot(HashCode value) {
        super(value);
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        Hashes.putHash(hasher, getValue());
    }
}
