package com.tyron.builder.security.internal;

import java.io.IOException;

public class EmptyPublicKeyService implements PublicKeyService {
    private final static EmptyPublicKeyService EMPTY = new EmptyPublicKeyService();

    private EmptyPublicKeyService() {

    }

    public static EmptyPublicKeyService getInstance() {
        return EMPTY;
    }

    @Override
    public void findByLongId(long keyId, PublicKeyResultBuilder builder) {

    }

    @Override
    public void findByFingerprint(byte[] fingerprint, PublicKeyResultBuilder builder) {

    }

    @Override
    public void close() throws IOException {

    }
}
