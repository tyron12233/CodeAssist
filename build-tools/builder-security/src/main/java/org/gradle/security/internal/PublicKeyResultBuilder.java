package org.gradle.security.internal;

import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;

public interface PublicKeyResultBuilder {
    void keyRing(PGPPublicKeyRing keyring);
    void publicKey(PGPPublicKey publicKey);
}
