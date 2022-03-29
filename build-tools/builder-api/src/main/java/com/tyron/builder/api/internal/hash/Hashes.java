package com.tyron.builder.api.internal.hash;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;

public class Hashes {
    public static HashCode signature(String signature) {
        Hasher hasher = Hashing.md5().newHasher();
        hasher.putString("SIGNATURE", StandardCharsets.UTF_8);
        hasher.putString(signature, StandardCharsets.UTF_8);
        return hasher.hash();
    }

}
