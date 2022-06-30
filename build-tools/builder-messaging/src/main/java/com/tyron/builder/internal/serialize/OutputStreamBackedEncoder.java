package com.tyron.builder.internal.serialize;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class OutputStreamBackedEncoder extends AbstractEncoder implements Closeable, FlushableEncoder {
    private final DataOutputStream outputStream;

    public OutputStreamBackedEncoder(OutputStream outputStream) {
        this.outputStream = new DataOutputStream(outputStream);
    }

    @Override
    public void writeLong(long value) throws IOException {
        outputStream.writeLong(value);
    }

    @Override
    public void writeInt(int value) throws IOException {
        outputStream.writeInt(value);
    }

    @Override
    public void writeBoolean(boolean value) throws IOException {
        outputStream.writeBoolean(value);
    }

    @Override
    public void writeString(CharSequence value) throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("Cannot encode a null string.");
        }
        outputStream.writeUTF(value.toString());
    }

    @Override
    public void writeByte(byte value) throws IOException {
        outputStream.writeByte(value);
    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int count) throws IOException {
        outputStream.write(bytes, offset, count);
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
    }
}
