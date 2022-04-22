package com.tyron.builder.cache.internal.btree;

import com.google.common.io.CountingOutputStream;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Allows a stream of bytes to be written to a particular location of some backing byte stream.
 */
public class ByteOutput {
    private final RandomAccessFile file;
    private final ResettableBufferedOutputStream bufferedOutputStream;
    private CountingOutputStream countingOutputStream;

    public ByteOutput(RandomAccessFile file) {
        this.file = file;
        bufferedOutputStream = new ResettableBufferedOutputStream(new RandomAccessFileOutputStream(file));
    }

    /**
     * Starts writing to the given offset. Can be beyond the current length of the file.
     */
    public DataOutputStream start(long offset) throws IOException {
        file.seek(offset);
        bufferedOutputStream.clear();
        countingOutputStream = new CountingOutputStream(bufferedOutputStream);
        return new DataOutputStream(countingOutputStream);
    }

    /**
     * Returns the number of byte written since {@link #start(long)} was called.
     */
    public long getBytesWritten() {
        return countingOutputStream.getCount();
    }

    /**
     * Finishes writing, flushing and resetting any buffered state
     */
    public void done() throws IOException {
        countingOutputStream.flush();
        countingOutputStream = null;
    }

    private static class ResettableBufferedOutputStream extends BufferedOutputStream {
        ResettableBufferedOutputStream(OutputStream output) {
            super(output);
        }

        void clear() {
            count = 0;
        }
    }

    /**
     * Writes to a {@link RandomAccessFile}. Each operation writes to and advances the current position of the file.
     *
     * <p>Closing this stream does not close the underlying file. Flushing this stream does nothing.
     */
    public static class RandomAccessFileOutputStream extends OutputStream {
        private final RandomAccessFile file;

        public RandomAccessFileOutputStream(RandomAccessFile file) {
            this.file = file;
        }

        @Override
        public void write(int i) throws IOException {
            file.write(i);
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            file.write(bytes);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            file.write(bytes, offset, length);
        }
    }
}