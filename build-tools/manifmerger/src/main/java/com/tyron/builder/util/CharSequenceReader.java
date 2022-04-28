package com.tyron.builder.util;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.CharBuffer;

/**
 * A {@link Reader} getting its data from a {@link CharSequence}. This light-weight implementation
 * is intended for single-thread use, doesn't have any synchronization, and does not throw {@link
 * IOException}.
 */
// This implementation is based on a package private Guava implementation (com.google.common.io),
// minus precondition checks, synchronization, IOException, plus annotations.
public final class CharSequenceReader extends Reader {
    private CharSequence seq;
    private int pos;
    private int mark;

    public CharSequenceReader(@NotNull CharSequence seq) {
        this.seq = seq;
    }

    private boolean hasRemaining() {
        return remaining() > 0;
    }

    private int remaining() {
        return seq.length() - pos;
    }

    @Override
    public int read(@NotNull CharBuffer target) {
        if (!hasRemaining()) {
            return -1;
        }
        int charsToRead = Math.min(target.remaining(), remaining());
        for (int i = 0; i < charsToRead; i++) {
            target.put(seq.charAt(pos++));
        }
        return charsToRead;
    }

    @Override
    public int read() {
        return hasRemaining() ? seq.charAt(pos++) : -1;
    }

    @Override
    public int read(@NotNull char[] cbuf, int off, int len) {
        if (!hasRemaining()) {
            return -1;
        }
        int charsToRead = Math.min(len, remaining());
        for (int i = 0; i < charsToRead; i++) {
            cbuf[off + i] = seq.charAt(pos++);
        }
        return charsToRead;
    }

    @Override
    public long skip(long n) {
        int charsToSkip = (int) Math.min(remaining(), n);
        pos += charsToSkip;
        return charsToSkip;
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readAheadLimit) {
        mark = pos;
    }

    @Override
    public void reset() {
        pos = mark;
    }

    @Override
    public void close() {
        seq = null;
    }
}
