package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;

/**
 * Hasher abstraction that can be fed different kinds of primitives that it then forwards directly to the hash function.
 * Inspired by the Google Guava project – https://github.com/google/guava.
 */
public interface PrimitiveHasher {
    /**
     * Feed a bunch of bytes into the hasher.
     */
    void putBytes(byte[] bytes);

    /**
     * Feed a given number of bytes into the hasher from the given offset.
     */
    void putBytes(byte[] bytes, int off, int len);

    /**
     * Feed a single byte into the hasher.
     */
    void putByte(byte value);

    /**
     * Feed an integer byte into the hasher.
     */
    void putInt(int value);

    /**
     * Feed a long value byte into the hasher.
     */
    void putLong(long value);

    /**
     * Feed a double value into the hasher.
     */
    void putDouble(double value);

    /**
     * Feed a boolean value into the hasher.
     */
    void putBoolean(boolean value);

    /**
     * Feed a string into the hasher.
     */
    void putString(CharSequence value);

    /**
     * Feed a hash code into the hasher.
     */
    void putHash(HashCode hashCode);

    /**
     * Returns the combined hash.
     */
    HashCode hash();
}
