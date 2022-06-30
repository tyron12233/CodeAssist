package com.tyron.builder.internal.serialize;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;

public abstract class AbstractEncoder implements Encoder {
    private EncoderStream stream;

    @Override
    public OutputStream getOutputStream() {
        if (stream == null) {
            stream = new EncoderStream();
        }
        return stream;
    }

    @Override
    public void writeBytes(byte[] bytes) throws IOException {
        writeBytes(bytes, 0, bytes.length);
    }

    @Override
    public void writeBinary(byte[] bytes) throws IOException {
        writeBinary(bytes, 0, bytes.length);
    }

    @Override
    public void writeBinary(byte[] bytes, int offset, int count) throws IOException {
        writeSmallInt(count);
        writeBytes(bytes, offset, count);
    }

    @Override
    public void encodeChunked(EncodeAction<Encoder> writeAction) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    public void writeSmallInt(int value) throws IOException {
        writeInt(value);
    }

    @Override
    public void writeSmallLong(long value) throws IOException {
        writeLong(value);
    }

    @Override
    public void writeNullableSmallInt(@Nullable Integer value) throws IOException {
        if (value == null) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeSmallInt(value);
        }
    }

    @Override
    public void writeNullableString(@Nullable CharSequence value) throws IOException {
        if (value == null) {
            writeBoolean(false);
        } else {
            writeBoolean(true);
            writeString(value.toString());
        }
    }

    private class EncoderStream extends OutputStream {
        @Override
        public void write(byte[] buffer) throws IOException {
            writeBytes(buffer);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            writeBytes(buffer, offset, length);
        }

        @Override
        public void write(int b) throws IOException {
            writeByte((byte) b);
        }
    }
}
