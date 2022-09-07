package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.base.Preconditions;
import com.google.common.io.Closer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

/**
 * An input stream that combines a list of input streams. The result when reading the entire
 * combined stream is the same as the result when reading the list of sub-streams in order.
 *
 * <p>This class is not thread-safe.
 */
final class CombinedInputStream extends InputStream {

    @NonNull private final ListIterator<InputStream> modifiableStreamList;
    @Nullable private InputStream currentStream;

    private final boolean newLinePadding;
    private int lastByteRead;

    /**
     * Creates a {@code CombinedInputStream} instance from the given list of input streams.
     *
     * <p>If {@code newLinePadding} is set to {@code true}, this class inserts '\n' characters
     * in-between sub-streams into the resulting combined stream . If a sub-stream already ends with
     * a '\n' character, then the inserting is skipped for that position.
     *
     * @param inputStreams the list of input streams to combine
     * @param newLinePadding whether '\n' characters should be inserted in-between input streams
     */
    public CombinedInputStream(@NonNull List<InputStream> inputStreams, boolean newLinePadding) {
        Preconditions.checkArgument(!inputStreams.isEmpty());
        inputStreams.forEach(Preconditions::checkNotNull);

        this.modifiableStreamList = new ArrayList<>(inputStreams).listIterator();
        this.currentStream = modifiableStreamList.next();

        this.newLinePadding = newLinePadding;
        this.lastByteRead = -1;
    }

    @Override
    public int read() throws IOException {
        //noinspection WhileLoopSpinsOnField
        while (currentStream != null) {
            int byteRead = currentStream.read();
            if (byteRead != -1) {
                return lastByteRead = byteRead;
            }
            advanceStream();
        }
        return lastByteRead = -1;
    }

    @Override
    public int read(@NonNull byte buffer[], int offset, int length) throws IOException {
        Preconditions.checkNotNull(buffer);
        Preconditions.checkArgument(offset >= 0);
        Preconditions.checkArgument(length >= 0);
        Preconditions.checkArgument(offset + length <= buffer.length);

        //noinspection WhileLoopSpinsOnField
        while (currentStream != null) {
            int byteCount = currentStream.read(buffer, offset, length);
            if (byteCount > 0) {
                lastByteRead = buffer[offset + byteCount - 1];
            }
            if (byteCount >= 0) {
                return byteCount;
            }
            advanceStream();
        }

        lastByteRead = -1;
        return -1;
    }

    private void advanceStream() throws IOException {
        Preconditions.checkNotNull(currentStream);
        currentStream.close();

        // When new line padding is enabled, if a sub-stream (except the last one) does not end with
        // a '\n' character, we will insert that character into the resulting combined stream
        if (newLinePadding) {
            if (modifiableStreamList.hasNext() && lastByteRead != '\n') {
                modifiableStreamList.add(new ByteArrayInputStream(new byte[] {'\n'}));
                // Move back the cursor to before the added stream
                modifiableStreamList.previous();
            }
        }

        currentStream = modifiableStreamList.hasNext() ? modifiableStreamList.next() : null;
    }

    @Override
    public int available() throws IOException {
        return currentStream != null ? currentStream.available() : 0;
    }

    @Override
    public void close() throws IOException {
        if (currentStream == null) {
            return;
        }

        try (Closer closer = Closer.create()) {
            closer.register(currentStream);
            modifiableStreamList.forEachRemaining(closer::register);
            currentStream = null;
        }
    }
}
