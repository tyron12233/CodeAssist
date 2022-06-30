package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class Hashes {
    
    private Hashes() {

    }

    private static final HashFunction MD5 = Hashing.md5();

    private static final HashFunction SHA1 = Hashing.sha1();

    private static final HashFunction SHA256 = Hashing.sha256();

    private static final HashFunction SHA512 = Hashing.sha512();


    private static final HashFunction DEFAULT = MD5;

    public static HashCode signature(String signature) {
        Hasher hasher = DEFAULT.newHasher();
        hasher.putString("SIGNATURE", StandardCharsets.UTF_8);
        hasher.putString(signature, StandardCharsets.UTF_8);
        return hasher.hash();
    }

    /**
     * Returns a hash code to use as a signature for a given type.
     */
    public static HashCode signature(Class<?> type) {
        return signature("CLASS:" + type.getName());
    }


    public static HashFunction sha1() {
        return SHA1;
    }

    /**
     * Returns a new {@link PrimitiveHasher} based on the default hashing implementation.
     */
    public static PrimitiveHasher newPrimitiveHasher() {
        Hasher hasher = DEFAULT.newHasher();
        return new PrimitiveHasher() {
            @Override
            public void putBytes(byte[] bytes) {
                hasher.putBytes(bytes);
            }

            @Override
            public void putBytes(byte[] bytes, int off, int len) {
                hasher.putBytes(bytes, off, len);
            }

            @Override
            public void putByte(byte value) {
                hasher.putByte(value);
            }

            @Override
            public void putInt(int value) {
                hasher.putInt(value);
            }

            @Override
            public void putLong(long value) {
                hasher.putLong(value);
            }

            @Override
            public void putDouble(double value) {
                hasher.putDouble(value);
            }

            @Override
            public void putBoolean(boolean value) {
                hasher.putBoolean(value);
            }

            @Override
            public void putString(CharSequence value) {
                hasher.putString(value, StandardCharsets.UTF_8);
            }

            @Override
            public void putHash(HashCode hashCode) {
                Hashes.putHash(hasher, hashCode);
            }

            @Override
            public HashCode hash() {
                return hasher.hash();
            }
        };
    }

    public static Hasher newHasher() {
        return DEFAULT.newHasher();
    }

    /**
     * Hash the contents of the given {@link java.io.InputStream} with the default hash function.
     */
    public static HashCode hashStream(InputStream stream) throws IOException {
        return new DefaultStreamHasher().hash(stream);
    }

    public static HashCode hashBytes(byte[] bytes) {
        return DEFAULT.hashBytes(bytes);
    }

    public static void putHash(Hasher hasher, HashCode another) {
        hasher.putInt(another.asBytes().length);
        hasher.putBytes(another.asBytes());
    }

    public static HashCode hashString(String text) {
        return newHasher().putString(text, StandardCharsets.UTF_8).hash();
    }

    public static String toCompactString(HashCode hashCode) {
        return new BigInteger(1, hashCode.asBytes()).toString(36);
    }
}
