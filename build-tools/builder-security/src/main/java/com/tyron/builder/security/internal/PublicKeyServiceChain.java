package com.tyron.builder.security.internal;

import com.google.common.collect.ImmutableList;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;

import java.util.List;

public class PublicKeyServiceChain implements PublicKeyService {
    private final static Logger LOGGER = Logging.getLogger(PublicKeyServiceChain.class);

    private final List<PublicKeyService> services;

    public static PublicKeyService of(PublicKeyService... delegates) {
        return new PublicKeyServiceChain(ImmutableList.copyOf(delegates));
    }

    private PublicKeyServiceChain(List<PublicKeyService> services) {
        this.services = services;
    }

    @Override
    public void findByLongId(long keyId, PublicKeyResultBuilder builder) {
        FirstMatchBuilder fmb = new FirstMatchBuilder(builder);
        for (PublicKeyService service : services) {
            service.findByLongId(keyId, fmb);
            if (fmb.hasResult) {
                return;
            }
        }
    }

    @Override
    public void findByFingerprint(byte[] fingerprint, PublicKeyResultBuilder builder) {
        FirstMatchBuilder fmb = new FirstMatchBuilder(builder);
        for (PublicKeyService service : services) {
            service.findByFingerprint(fingerprint, fmb);
            if (fmb.hasResult) {
                return;
            }
        }
    }

    @Override
    public void close() {
        for (PublicKeyService service : services) {
            try {
                service.close();
            } catch (Exception e) {
                LOGGER.warn("Cannot close service", e);
            }
        }
    }

    private static class FirstMatchBuilder implements PublicKeyResultBuilder {
        private final PublicKeyResultBuilder delegate;
        public boolean hasResult;

        private FirstMatchBuilder(PublicKeyResultBuilder delegate) {
            this.delegate = delegate;
        }

        @Override
        public void keyRing(PGPPublicKeyRing keyring) {
            delegate.keyRing(keyring);
            hasResult = true;
        }

        @Override
        public void publicKey(PGPPublicKey publicKey) {
            delegate.publicKey(publicKey);
            hasResult = true;
        }
    }
}
