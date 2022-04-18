package com.tyron.builder.internal.serialize.kryo;

import com.esotericsoftware.kryo.io.Output;
import com.tyron.builder.internal.serialize.AbstractEncoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.FlushableEncoder;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

public class KryoBackedEncoder extends AbstractEncoder implements FlushableEncoder, Closeable {
    private final Output output;
    private KryoBackedEncoder nested;

    public KryoBackedEncoder(OutputStream outputStream) {
        this(outputStream, 4096);
    }

    public KryoBackedEncoder(OutputStream outputStream, int bufferSize) {
        output = new Output(outputStream, bufferSize);
    }

    @Override
    public void writeByte(byte value) {
        output.writeByte(value);
    }

    @Override
    public void writeBytes(byte[] bytes, int offset, int count) {
        output.writeBytes(bytes, offset, count);
    }

    @Override
    public void writeLong(long value) {
        output.writeLong(value);
    }

    @Override
    public void writeSmallLong(long value) {
        output.writeLong(value, true);
    }

    @Override
    public void writeInt(int value) {
        output.writeInt(value);
    }

    @Override
    public void writeSmallInt(int value) {
        output.writeInt(value, true);
    }

    @Override
    public void writeBoolean(boolean value) {
        output.writeBoolean(value);
    }

    @Override
    public void writeString(CharSequence value) {
        if (value == null) {
            throw new IllegalArgumentException("Cannot encode a null string.");
        }
        output.writeString(value.toString());
    }

    @Override
    public void writeNullableString(@Nullable CharSequence value) {
        output.writeString(value == null ? null : value.toString());
    }

    @Override
    public void encodeChunked(EncodeAction<Encoder> writeAction) throws Exception {
        if (nested == null) {
            nested = new KryoBackedEncoder(new OutputStream() {
                @Override
                public void write(byte[] buffer, int offset, int length) {
                    if (length == 0) {
                        return;
                    }
                    writeSmallInt(length);
                    writeBytes(buffer, offset, length);
                }

                @Override
                public void write(byte[] buffer) throws IOException {
                    write(buffer, 0, buffer.length);
                }

                @Override
                public void write(int b) {
                    throw new UnsupportedOperationException();
                }
            });
        }
        writeAction.write(nested);
        nested.flush();
        writeSmallInt(0);
    }

    /**
     * Returns the total number of bytes written by this encoder, some of which may still be buffered.
     */
    public long getWritePosition() {
        return output.total();
    }

    @Override
    public void flush() {
        output.flush();
    }

    @Override
    public void close() {
        output.close();
    }
}
