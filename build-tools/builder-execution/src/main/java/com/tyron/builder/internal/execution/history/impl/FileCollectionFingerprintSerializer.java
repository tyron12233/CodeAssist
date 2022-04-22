package com.tyron.builder.internal.execution.history.impl;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Interner;
import com.google.common.hash.HashCode;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileSystemLocationFingerprint;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.HashCodeSerializer;
import com.tyron.builder.internal.serialize.Serializer;

import java.io.IOException;
import java.util.Map;

public class FileCollectionFingerprintSerializer implements Serializer<FileCollectionFingerprint> {

    private final FingerprintMapSerializer fingerprintMapSerializer;
    private final Interner<String> stringInterner;
    private final HashCodeSerializer hashCodeSerializer;

    public FileCollectionFingerprintSerializer(Interner<String> stringInterner) {
        this.fingerprintMapSerializer = new FingerprintMapSerializer(stringInterner);
        this.stringInterner = stringInterner;
        this.hashCodeSerializer = new HashCodeSerializer();
    }

    @Override
    public FileCollectionFingerprint read(Decoder decoder) throws IOException {
        Map<String, FileSystemLocationFingerprint> fingerprints = fingerprintMapSerializer.read(decoder);
        if (fingerprints.isEmpty()) {
            return FileCollectionFingerprint.EMPTY;
        }
        ImmutableMultimap<String, HashCode> rootHashes = readRootHashes(decoder);
        HashCode strategyConfigurationHash = hashCodeSerializer.read(decoder);
        return new SerializableFileCollectionFingerprint(fingerprints, rootHashes, strategyConfigurationHash);
    }

    private ImmutableMultimap<String, HashCode> readRootHashes(Decoder decoder) throws IOException {
        int numberOfRoots = decoder.readSmallInt();
        if (numberOfRoots == 0) {
            return ImmutableMultimap.of();
        }
        ImmutableMultimap.Builder<String, HashCode> builder = ImmutableMultimap.builder();
        for (int i = 0; i < numberOfRoots; i++) {
            String absolutePath = stringInterner.intern(decoder.readString());
            HashCode rootHash = hashCodeSerializer.read(decoder);
            builder.put(absolutePath, rootHash);
        }
        return builder.build();
    }

    @Override
    public void write(Encoder encoder, FileCollectionFingerprint value) throws Exception {
        fingerprintMapSerializer.write(encoder, value.getFingerprints());
        if (!value.getFingerprints().isEmpty()) {
            writeRootHashes(encoder, value.getRootHashes());
            hashCodeSerializer.write(encoder, ((SerializableFileCollectionFingerprint) value).getStrategyConfigurationHash());
        }
    }

    private void writeRootHashes(Encoder encoder, ImmutableMultimap<String, HashCode> rootHashes) throws IOException {
        encoder.writeSmallInt(rootHashes.size());
        for (Map.Entry<String, HashCode> entry : rootHashes.entries()) {
            encoder.writeString(entry.getKey());
            hashCodeSerializer.write(encoder, entry.getValue());
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) {
            return false;
        }

        FileCollectionFingerprintSerializer rhs = (FileCollectionFingerprintSerializer) obj;
        return Objects.equal(fingerprintMapSerializer, rhs.fingerprintMapSerializer)
               && Objects.equal(hashCodeSerializer, rhs.hashCodeSerializer);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), fingerprintMapSerializer, hashCodeSerializer);
    }
}