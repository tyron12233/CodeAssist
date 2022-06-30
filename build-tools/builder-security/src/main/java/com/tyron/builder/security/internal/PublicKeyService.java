package com.tyron.builder.security.internal;

import java.io.Closeable;

public interface PublicKeyService extends Closeable {
    void findByLongId(long keyId, PublicKeyResultBuilder builder);
    void findByFingerprint(byte[] fingerprint, PublicKeyResultBuilder builder);
}
