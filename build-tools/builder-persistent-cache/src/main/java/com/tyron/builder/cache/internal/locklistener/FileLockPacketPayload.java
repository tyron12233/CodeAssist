package com.tyron.builder.cache.internal.locklistener;

import static com.tyron.builder.cache.internal.locklistener.FileLockPacketType.UNKNOWN;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.UncheckedIOException;

public class FileLockPacketPayload {

    public static final int MAX_BYTES = 1 + 8 + 1; // protocolVersion + lockId + type
    private static final byte PROTOCOL_VERSION = 1;
    private static final ImmutableList<FileLockPacketType> TYPES = ImmutableList.copyOf(FileLockPacketType.values());

    private final long lockId;
    private final FileLockPacketType type;

    private FileLockPacketPayload(long lockId, FileLockPacketType type) {
        this.lockId = lockId;
        this.type = type;
    }

    public long getLockId() {
        return lockId;
    }

    public FileLockPacketType getType() {
        return type;
    }

    public static byte[] encode(long lockId, FileLockPacketType type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dataOutput = new DataOutputStream(out);
        try {
            dataOutput.writeByte(PROTOCOL_VERSION);
            dataOutput.writeLong(lockId);
            dataOutput.writeByte(type.ordinal());
            dataOutput.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode lockId " + lockId + " and type " + type, e);
        }
        return out.toByteArray();
    }

    public static FileLockPacketPayload decode(byte[] bytes, int length) throws IOException {
        DataInputStream dataInput = new DataInputStream(new ByteArrayInputStream(bytes));
        byte version = dataInput.readByte();
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException(String.format("Unexpected protocol version %s received in lock contention notification message", version));
        }
        long lockId = dataInput.readLong();
        FileLockPacketType type = readType(dataInput, length);
        return new FileLockPacketPayload(lockId, type);
    }

    private static FileLockPacketType readType(DataInputStream dataInput, int length) throws IOException {
        if (length < MAX_BYTES) {
            return UNKNOWN;
        }
        try {
            int ordinal = dataInput.readByte();
            if (ordinal < TYPES.size()) {
                return TYPES.get(ordinal);
            }
        } catch (EOFException ignore) {
            // old versions don't send a type
        }
        return UNKNOWN;
    }

}