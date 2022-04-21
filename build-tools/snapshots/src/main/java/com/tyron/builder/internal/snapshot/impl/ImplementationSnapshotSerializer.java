package com.tyron.builder.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.HashCodeSerializer;
import com.tyron.builder.internal.serialize.Serializer;

public class ImplementationSnapshotSerializer implements Serializer<ImplementationSnapshot> {

    private enum Impl implements Serializer<ImplementationSnapshot> {
        DEFAULT {
            @Override
            protected ImplementationSnapshot doRead(String typeName, Decoder decoder) throws Exception {
                HashCode classLoaderHash = hashCodeSerializer.read(decoder);
                return new KnownImplementationSnapshot(typeName, classLoaderHash);
            }

            @Override
            public void writeAdditionalData(Encoder encoder, ImplementationSnapshot implementationSnapshot) throws Exception {
                hashCodeSerializer.write(encoder, implementationSnapshot.getClassLoaderHash());
            }
        },
        UNKNOWN_CLASSLOADER {
            @Override
            protected ImplementationSnapshot doRead(String typeName, Decoder decoder) {
                return new UnknownClassloaderImplementationSnapshot(typeName);
            }
        },
        LAMBDA {
            @Override
            protected ImplementationSnapshot doRead(String typeName, Decoder decoder) {
                return new LambdaImplementationSnapshot(typeName);
            }
        };

        @Override
        public void write(Encoder encoder, ImplementationSnapshot implementationSnapshot) throws Exception {
            encoder.writeString(implementationSnapshot.getTypeName());
            writeAdditionalData(encoder, implementationSnapshot);
        }

        @Override
        public ImplementationSnapshot read(Decoder decoder) throws Exception {
            String typeName = decoder.readString();
            return doRead(typeName, decoder);
        }

        protected final Serializer<HashCode> hashCodeSerializer = new HashCodeSerializer();

        protected abstract ImplementationSnapshot doRead(String typeName, Decoder decoder) throws Exception;

        protected void writeAdditionalData(Encoder encoder, ImplementationSnapshot implementationSnapshot) throws Exception {
        }
    }

    @Override
    public ImplementationSnapshot read(Decoder decoder) throws Exception {
        Impl serializer = Impl.values()[decoder.readSmallInt()];
        return serializer.read(decoder);
    }

    @Override
    public void write(Encoder encoder, ImplementationSnapshot implementationSnapshot) throws Exception {
        Impl serializer = determineSerializer(implementationSnapshot);
        encoder.writeSmallInt(serializer.ordinal());
        serializer.write(encoder, implementationSnapshot);
    }

    private static Impl determineSerializer(ImplementationSnapshot implementationSnapshot) {
        if (implementationSnapshot instanceof KnownImplementationSnapshot) {
            return Impl.DEFAULT;
        }
        if (implementationSnapshot instanceof UnknownClassloaderImplementationSnapshot) {
            return Impl.UNKNOWN_CLASSLOADER;
        }
        if (implementationSnapshot instanceof LambdaImplementationSnapshot) {
            return Impl.LAMBDA;
        }
        throw new IllegalArgumentException("Unknown implementation snapshot type: " + implementationSnapshot.getClass().getName());
    }
}