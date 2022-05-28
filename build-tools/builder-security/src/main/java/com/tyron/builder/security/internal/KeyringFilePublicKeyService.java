package com.tyron.builder.security.internal;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class KeyringFilePublicKeyService implements PublicKeyService {
    private final static Logger LOGGER = Logging.getLogger(KeyringFilePublicKeyService.class);
    private final File keyRingFile;
    private LoadedKeys keys;

    public KeyringFilePublicKeyService(File keyRingFile) {
        this.keyRingFile = keyRingFile;
    }

    private LoadedKeys load() {
        synchronized (this) {
            if (keys == null) {
                try {
                    List<PGPPublicKeyRing> keyrings = SecuritySupport.loadKeyRingFile(keyRingFile);
                    Map<Fingerprint, PGPPublicKeyRing> keyToKeyringBuilder = Maps.newHashMap();
                    ImmutableMultimap.Builder<Long, PGPPublicKeyRing> longIdLongPGPPublicKeyBuilder = ImmutableListMultimap.builder();

                    for (PGPPublicKeyRing keyring : keyrings) {
                        Iterator<PGPPublicKey> it = keyring.getPublicKeys();
                        while (it.hasNext()) {
                            PGPPublicKey key = it.next();
                            Fingerprint fingerprint = Fingerprint.of(key);
                            keyToKeyringBuilder.put(fingerprint, keyring);
                            longIdLongPGPPublicKeyBuilder.put(key.getKeyID(), keyring);
                        }
                    }
                    keys = new LoadedKeys(ImmutableMap.copyOf(keyToKeyringBuilder), longIdLongPGPPublicKeyBuilder.build());
                    LOGGER.info("Loaded {} keys from {}", keys.keyToKeyring.size(), keyRingFile);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return keys;
        }
    }

    @Override
    public void close() {

    }

    @Override
    public void findByLongId(long keyId, PublicKeyResultBuilder builder) {
        for (PGPPublicKeyRing keyring : load().longIdToPublicKeys.get(keyId)) {
            builder.keyRing(keyring);
            Iterator<PGPPublicKey> pkIt = keyring.getPublicKeys();
            while (pkIt.hasNext()) {
                PGPPublicKey key = pkIt.next();
                if (key.getKeyID() == keyId) {
                    builder.publicKey(key);
                }
            }
        }
    }

    @Override
    public void findByFingerprint(byte[] bytes, PublicKeyResultBuilder builder) {
        Fingerprint fingerprint = Fingerprint.wrap(bytes);
        PGPPublicKeyRing keyring = load().keyToKeyring.get(fingerprint);
        if (keyring != null) {
            builder.keyRing(keyring);
            Iterator<PGPPublicKey> pkIt = keyring.getPublicKeys();
            while (pkIt.hasNext()) {
                PGPPublicKey key = pkIt.next();
                if (Arrays.equals(key.getFingerprint(), bytes)) {
                    builder.publicKey(key);
                }
            }
        }
    }

    private static class LoadedKeys {
        private final Map<Fingerprint, PGPPublicKeyRing> keyToKeyring;
        private final Multimap<Long, PGPPublicKeyRing> longIdToPublicKeys;

        public LoadedKeys(Map<Fingerprint, PGPPublicKeyRing> keyToKeyring, Multimap<Long, PGPPublicKeyRing> longIdToPublicKeys) {
            this.keyToKeyring = keyToKeyring;
            this.longIdToPublicKeys = longIdToPublicKeys;
        }
    }
}
