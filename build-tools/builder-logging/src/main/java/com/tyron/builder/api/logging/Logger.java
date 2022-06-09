package com.tyron.builder.api.logging;

/**
 * <p>An extension to the SLF4J {@code Logger} interface, which adds the {@code quiet} and {@code lifecycle} log
 * levels.</p>
 *
 * <p>You can obtain a {@code Logger} instance using {@link Logging#getLogger(Class)} or {@link
 * Logging#getLogger(String)}. A {@code Logger} instance is also available through {@link
 * com.tyron.builder.api.Project#getLogger()}, {@link com.tyron.builder.api.Task#getLogger()} and {@link
 * com.tyron.builder.api.Script#getLogger()}.</p>
 * <br>
 * <p><b>CAUTION!</b>
 * Logging sensitive information (credentials, tokens, certain environment variables) above {@link Logger#debug} level is a security vulnerability.
 * See <a href="https://docs.gradle.org/current/userguide/logging.html#sec:debug_security">our recommendations</a> for more information.
 * </p>
 */
public interface Logger extends org.slf4j.Logger {
    /**
     * Returns true if lifecycle log level is enabled for this logger.
     */
    boolean isLifecycleEnabled();

    /**
     * Multiple-parameters friendly debug method
     *
     * @param message the log message
     * @param objects the log message parameters
     */
    @Override
    void debug(String message, Object... objects);

    /**
     * Logs the given message at lifecycle log level.
     *
     * @param message the log message.
     */
    void lifecycle(String message);

    /**
     * Logs the given message at lifecycle log level.
     *
     * @param message the log message.
     * @param objects the log message parameters.
     */
    void lifecycle(String message, Object... objects);

    /**
     * Logs the given message at lifecycle log level.
     *
     * @param message the log message.
     * @param throwable the exception to log.
     */
    void lifecycle(String message, Throwable throwable);

    /**
     * Returns true if quiet log level is enabled for this logger.
     */
    boolean isQuietEnabled();

    /**
     * Logs the given message at quiet log level.
     *
     * @param message the log message.
     */
    void quiet(String message);

    /**
     * Logs the given message at quiet log level.
     *
     * @param message the log message.
     * @param objects the log message parameters.
     */
    void quiet(String message, Object... objects);

    /**
     * Logs the given message at info log level.
     *
     * @param message the log message.
     * @param objects the log message parameters.
     */
    @Override
    void info(String message, Object... objects);

    /**
     * Logs the given message at quiet log level.
     *
     * @param message the log message.
     * @param throwable the exception to log.
     */
    void quiet(String message, Throwable throwable);

    /**
     * Returns true if the given log level is enabled for this logger.
     */
    boolean isEnabled(LogLevel level);

    /**
     * Logs the given message at the given log level.
     *
     * @param level the log level.
     * @param message the log message.
     */
    void log(LogLevel level, String message);

    /**
     * Logs the given message at the given log level.
     *
     * @param level the log level.
     * @param message the log message.
     * @param objects the log message parameters.
     */
    void log(LogLevel level, String message, Object... objects);

    /**
     * Logs the given message at the given log level.
     *
     * @param level the log level.
     * @param message the log message.
     * @param throwable the exception to log.
     */
    void log(LogLevel level, String message, Throwable throwable);
}