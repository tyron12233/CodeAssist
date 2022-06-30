package com.tyron.builder.internal.serialize;

import com.google.common.hash.HashCode;

import java.io.IOException;

public class HashCodeSerializer extends AbstractSerializer<HashCode> {
    @Override
    public HashCode read(Decoder decoder) throws IOException {
        byte hashSize = decoder.readByte();
        byte[] hash = new byte[hashSize];
        decoder.readBytes(hash);
        return HashCode.fromBytes(hash);
    }

    @Override
    public void write(Encoder encoder, HashCode value) throws IOException {
        byte[] hash = value.asBytes();
        encoder.writeByte((byte) hash.length);
        encoder.writeBytes(hash);
    }
}