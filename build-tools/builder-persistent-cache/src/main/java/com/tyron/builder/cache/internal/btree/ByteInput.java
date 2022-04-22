package com.tyron.builder.cache.internal.btree;

import com.google.common.io.CountingInputStream;

import org.apache.commons.io.input.RandomAccessFileInputStream;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Allows a stream of bytes to be read from a particular location of some backing byte stream.
 */
class ByteInput {
    private final RandomAccessFile file;
    private final ResettableBufferedInputStream bufferedInputStream;
    private CountingInputStream countingInputStream;

    public ByteInput(RandomAccessFile file) {
        this.file = file;
        bufferedInputStream = new ResettableBufferedInputStream(new RandomAccessFileInputStream(file));
    }

    /**
     * Starts reading from the given offset.
     */
    public DataInputStream start(long offset) throws IOException {
        file.seek(offset);
        bufferedInputStream.clear();
        countingInputStream = new CountingInputStream(bufferedInputStream);
        return new DataInputStream(countingInputStream);
    }

    /**
     * Returns the number of bytes read since {@link #start(long)} was called.
     */
    public long getBytesRead() {
        return countingInputStream.getCount();
    }

    /**
     * Finishes reading, resetting any buffered state.
     */
    public void done() {
        countingInputStream = null;
    }

    private static class ResettableBufferedInputStream extends BufferedInputStream {
        ResettableBufferedInputStream(InputStream input) {
            super(input);
        }

        void clear() {
            count = 0;
            pos = 0;
        }
    }
}