package com.tyron.builder.internal.serialize;

import java.io.*;

public class InputStreamBackedDecoder extends AbstractDecoder implements Decoder, Closeable {
    private final DataInputStream inputStream;

    public InputStreamBackedDecoder(InputStream inputStream) {
        this(new DataInputStream(inputStream));
    }

    public InputStreamBackedDecoder(DataInputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    protected int maybeReadBytes(byte[] buffer, int offset, int count) throws IOException {
        return inputStream.read(buffer, offset, count);
    }

    @Override
    protected long maybeSkip(long count) throws IOException {
        return inputStream.skip(count);
    }

    @Override
    public long readLong() throws IOException {
        return inputStream.readLong();
    }

    @Override
    public int readInt() throws EOFException, IOException {
        return inputStream.readInt();
    }

    @Override
    public boolean readBoolean() throws EOFException, IOException {
        return inputStream.readBoolean();
    }

    @Override
    public String readString() throws EOFException, IOException {
        return inputStream.readUTF();
    }

    @Override
    public byte readByte() throws IOException {
        return (byte)(inputStream.readByte() & 0xff);
    }

    @Override
    public void readBytes(byte[] buffer, int offset, int count) throws IOException {
        inputStream.readFully(buffer, offset, count);
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
