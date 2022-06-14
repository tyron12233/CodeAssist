package com.tyron.builder.internal.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Reads from a {@link RandomAccessFile}. Each operation reads from and advances the current position of the file.
 *
 * <p>Closing this stream does not close the underlying file.
 */
public class RandomAccessFileInputStream extends InputStream {
    private final RandomAccessFile file;

    public RandomAccessFileInputStream(RandomAccessFile file) {
        this.file = file;
    }

    @Override
    public long skip(long n) throws IOException {
        file.seek(file.getFilePointer() + n);
        return n;
    }

    @Override
    public int read(byte[] bytes) throws IOException {
        return file.read(bytes);
    }

    @Override
    public int read() throws IOException {
        return file.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        return file.read(bytes, offset, length);
    }
}
