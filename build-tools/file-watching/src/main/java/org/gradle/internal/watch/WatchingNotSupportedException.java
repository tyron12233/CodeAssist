package org.gradle.internal.watch;

public class WatchingNotSupportedException extends RuntimeException {
    public WatchingNotSupportedException(String message) {
        super(message);
    }

    public WatchingNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }
}
