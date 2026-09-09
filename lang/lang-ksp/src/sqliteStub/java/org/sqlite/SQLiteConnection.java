package org.sqlite;

/**
 * On-device (ART) stub type — see {@link SQLiteJDBCLoader}. Room's {@code DatabaseVerifier} references
 * {@code org.sqlite.SQLiteConnection} as the return type of {@link JDBC#createConnection}; the stub's
 * {@code createConnection} always throws, so an instance is never produced. This only needs to exist so
 * Room's compiled code links.
 */
public abstract class SQLiteConnection {
}
