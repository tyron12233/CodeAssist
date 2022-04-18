package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;

import java.io.File;

public interface ChecksumService {
    HashCode md5(File file);

    HashCode sha1(File file);

    HashCode sha256(File file);

    HashCode sha512(File file);

    HashCode hash(File src, String algorithm);
}