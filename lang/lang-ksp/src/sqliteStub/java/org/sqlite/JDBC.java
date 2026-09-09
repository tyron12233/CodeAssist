package org.sqlite;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * On-device (ART) stub of {@code org.xerial:sqlite-jdbc}'s JDBC driver — see {@link SQLiteJDBCLoader}.
 *
 * <p>Room's {@code DatabaseVerifier} static initializer registers/looks up a {@code jdbc:sqlite:} driver and
 * asserts it is an {@code org.sqlite.JDBC}, so the stub self-registers and matches that type. Every
 * connection attempt throws a {@code SQLException} (a checked {@code Exception}, NOT an {@code Error}), which
 * Room's {@code create()} catches — routing to the "verification unavailable" fallback instead of crashing.
 */
public class JDBC implements Driver {
    private static final String PREFIX = "jdbc:sqlite:";

    static {
        try {
            DriverManager.registerDriver(new JDBC());
        } catch (SQLException ignored) {
            // A registration failure only means DriverManager.getDriver won't find us; Room then throws its
            // own IllegalStateException, still caught by create(). Nothing to do here.
        }
    }

    /** Room's static initializer calls this before looking the driver up. */
    public static boolean isValidURL(String url) {
        return url != null && url.toLowerCase().startsWith(PREFIX);
    }

    /** Room's {@code create()} calls this inside a try/catch; throwing routes it to the graceful fallback. */
    public static SQLiteConnection createConnection(String url, Properties props) throws SQLException {
        throw new SQLException("SQLite query verification is not available on this device");
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        throw new SQLException("SQLite query verification is not available on this device");
    }

    @Override
    public boolean acceptsURL(String url) {
        return isValidURL(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 0;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("org.sqlite");
    }
}
