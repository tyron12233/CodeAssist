package org.sqlite;

/**
 * On-device (ART) stub of {@code org.xerial:sqlite-jdbc}'s native loader.
 *
 * <p>The real class loads a native SQLite library, but sqlite-jdbc ships no build for Android/aarch64, so on
 * ART {@code initialize()} throws "No native library found for os.name=Linux-Android". Room's
 * {@code DatabaseVerifier} calls it from a class <b>static initializer</b> with no fallback, so that throw
 * aborts class-loading (an {@code ExceptionInInitializerError}) and crashes the whole KSP run before Room's
 * own graceful "verification unavailable" path can run.
 *
 * <p>This stub never touches a native and never throws, so {@code DatabaseVerifier}'s static init succeeds.
 * The subsequent connection attempt fails through {@link JDBC#createConnection} with a caught
 * {@code SQLException}, which routes Room to its {@code CANNOT_CREATE_VERIFICATION_DATABASE} fallback: it
 * generates the {@code _Impl} code without compile-time SQL query verification. The generated code is
 * identical either way; only the build-time SQL check is lost.
 */
public final class SQLiteJDBCLoader {
    private SQLiteJDBCLoader() {}

    /** No native load, no throw — Room only needs this to not blow up its static initializer. */
    public static boolean initialize() {
        return false;
    }

    public static boolean isNativeMode() {
        return false;
    }

    public static String getVersion() {
        return "0.0.0-stub";
    }
}
