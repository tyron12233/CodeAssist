package dev.ide.lang.jdt.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * ART-safe backports of the Java 9 and Java 11 stream methods that Android only added in API level 33:
 * {@link InputStream#readAllBytes()}, {@link InputStream#readNBytes(int)},
 * {@link InputStream#readNBytes(byte[], int, int)}, {@link InputStream#transferTo(OutputStream)},
 * {@link InputStream#nullInputStream()} and {@link OutputStream#nullOutputStream()}.
 *
 * <p>CodeAssist's {@code minSdk} is 26, and core-library desugaring does not cover {@code java.io}, so on an
 * API 26 to 32 device these calls resolve to nothing and throw {@link NoSuchMethodError} at runtime. Two
 * bundled libraries are built for a desktop JVM and reach them on their first use:
 *
 * <ul>
 *   <li>ecj on its very first parse. {@code Parser.<clinit>} loads the parser tables through
 *       {@code Util.getInputStreamAsByteArray(InputStream)}, whose whole body is a single
 *       {@code input.readAllBytes()}. The error surfaces inside the static initializer as an uncatchable
 *       {@link ExceptionInInitializerError}, which disables all Java parsing, indexing and analysis.</li>
 *   <li>JGit on every repository open. {@code FileBasedConfig.load()} reads the config through
 *       {@code IO.readFully(File)}, and the git wire protocol reads every packet line through
 *       {@code IO.readFully(InputStream, byte[], int, int)}, so both {@code clone} and {@code init} fail.</li>
 * </ul>
 *
 * <p>The build rewrites those call sites into {@code INVOKESTATIC} calls to the methods here, which use the
 * API 1 {@code read}/{@code write} primitives instead. See the {@code EclipseStreamArtPass} bytecode pass in
 * {@code build-logic}. The rewrite only touches the bundled {@code org.eclipse.} jars; desktop and tests keep
 * the real JDK methods.
 */
public final class InputStreamCompat {

    private InputStreamCompat() {}

    private static final int CHUNK = 8192;

    /** Reads {@code input} to end of stream. Equivalent to {@link InputStream#readAllBytes()}. */
    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(CHUNK, input.available()));
        byte[] buffer = new byte[CHUNK];
        int read;
        while ((read = input.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Reads up to {@code length} bytes, blocking until that many are read or the stream ends. Equivalent to
     * {@link InputStream#readNBytes(int)}.
     */
    public static byte[] readNBytes(InputStream input, int length) throws IOException {
        if (length < 0) throw new IllegalArgumentException("length < 0: " + length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, Math.min(length, CHUNK)));
        byte[] buffer = new byte[Math.min(length, CHUNK)];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(remaining, buffer.length));
            if (read == -1) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    /**
     * Reads up to {@code length} bytes into {@code buffer} at {@code offset}, blocking until that many are
     * read or the stream ends, and returns how many were read. Equivalent to
     * {@link InputStream#readNBytes(byte[], int, int)}.
     */
    public static int readNBytes(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        checkBounds(buffer.length, offset, length);
        int total = 0;
        while (total < length) {
            int read = input.read(buffer, offset + total, length - total);
            if (read == -1) break;
            total += read;
        }
        return total;
    }

    /**
     * Copies the remainder of {@code input} to {@code output} and returns the number of bytes copied.
     * Equivalent to {@link InputStream#transferTo(OutputStream)}.
     */
    public static long transferTo(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[CHUNK];
        long transferred = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            transferred += read;
        }
        return transferred;
    }

    /**
     * A stream that is already at end of stream, and that reports itself closed after {@code close()}.
     * Equivalent to {@link InputStream#nullInputStream()}.
     */
    public static InputStream nullInputStream() {
        return new InputStream() {
            private volatile boolean closed;

            @Override
            public int read() throws IOException {
                ensureOpen(closed);
                return -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                checkBounds(buffer.length, offset, length);
                ensureOpen(closed);
                return length == 0 ? 0 : -1;
            }

            @Override
            public int available() throws IOException {
                ensureOpen(closed);
                return 0;
            }

            @Override
            public long skip(long count) throws IOException {
                ensureOpen(closed);
                return 0L;
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    /**
     * A stream that discards everything written to it, and that reports itself closed after {@code close()}.
     * Equivalent to {@link OutputStream#nullOutputStream()}.
     */
    public static OutputStream nullOutputStream() {
        return new OutputStream() {
            private volatile boolean closed;

            @Override
            public void write(int b) throws IOException {
                ensureOpen(closed);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                checkBounds(buffer.length, offset, length);
                ensureOpen(closed);
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    private static void ensureOpen(boolean closed) throws IOException {
        if (closed) throw new IOException("Stream closed");
    }

    /** The range check {@code java.util.Objects.checkFromIndexSize} performs, which is itself API 30. */
    private static void checkBounds(int size, int offset, int length) {
        if (offset < 0 || length < 0 || length > size - offset) {
            throw new IndexOutOfBoundsException("offset " + offset + ", length " + length + ", size " + size);
        }
    }
}
