package org.gradle.security.internal;

import java.io.Closeable;

public interface PublicKeyService extends Closeable {
    void findByLongId(long keyId, PublicKeyResultBuilder builder);
    void findByFingerprint(byte[] fingerprint, PublicKeyResultBuilder builder);
}
