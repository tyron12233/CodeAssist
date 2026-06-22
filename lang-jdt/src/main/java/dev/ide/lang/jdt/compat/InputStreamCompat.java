package dev.ide.lang.jdt.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ART-safe backports of {@link java.io.InputStream#readAllBytes()} and
 * {@link java.io.InputStream#readNBytes(int)}.
 *
 * <p>Both methods were added to {@code InputStream} in Java 9, but on Android they only exist from API
 * level 33. CodeAssist's {@code minSdk} is 26 and core-library desugaring is intentionally off, so on an
 * API 26 to 32 device the calls resolve to nothing and throw {@link NoSuchMethodError} at runtime. Eclipse
 * ecj reaches {@code readAllBytes()} on its very first parse: {@code Parser.<clinit>} loads the parser
 * tables through {@code Util.getInputStreamAsByteArray(InputStream)}, whose whole body is a single
 * {@code input.readAllBytes()}. The {@link NoSuchMethodError} surfaces inside the static initializer as an
 * uncatchable {@link ExceptionInInitializerError}, which disables ALL Java parsing, indexing and analysis.
 *
 * <p>The build rewrites ecj's {@code INVOKEVIRTUAL java/io/InputStream.readAllBytes ()[B} (and the matching
 * {@code readNBytes (I)[B}) call sites into {@code INVOKESTATIC} calls to the methods here, which read the
 * stream with the API-1 {@code read(byte[])} primitives instead. See the {@code EcjInputStreamArtPass}
 * bytecode pass in {@code build-logic}. The relocation only touches ecj; desktop and tests keep the real
 * JDK methods.
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
}
