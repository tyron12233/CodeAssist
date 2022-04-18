package com.tyron.builder.internal.hash;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

public class DefaultStreamHasher implements StreamHasher {
    private static final HashCode SIGNATURE = Hashes.signature(DefaultStreamHasher.class);

    private final Queue<byte[]> buffers = new ArrayBlockingQueue<byte[]>(16);

    @Override
    public HashCode hash(InputStream inputStream) {
        try {
            return doHash(inputStream, ByteStreams.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create MD5 hash for file content.", e);
        }
    }

    @Override
    public HashCode hashCopy(InputStream inputStream, OutputStream outputStream) throws IOException {
        return doHash(inputStream, outputStream);
    }

    private HashCode doHash(InputStream inputStream, OutputStream outputStream) throws IOException {
        byte[] buffer = takeBuffer();
        try {
            PrimitiveHasher hasher = Hashes.newPrimitiveHasher();
            hasher.putHash(SIGNATURE);
            while (true) {
                int nread = inputStream.read(buffer);
                if (nread < 0) {
                    break;
                }
                outputStream.write(buffer, 0, nread);
                hasher.putBytes(buffer, 0, nread);
            }
            return hasher.hash();
        } finally {
            returnBuffer(buffer);
        }
    }

    private void returnBuffer(byte[] buffer) {
        // Retain buffer if there is capacity in the queue, otherwise discard
        buffers.offer(buffer);
    }

    private byte[] takeBuffer() {
        byte[] buffer = buffers.poll();
        if (buffer == null) {
            buffer = new byte[8192];
        }
        return buffer;
    }
}