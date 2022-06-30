package com.tyron.layoutpreview2;

/**
 * Thrown when there's an error inflating the view.
 */
public class InflateException extends RuntimeException {

    public InflateException() {

    }

    public InflateException(Throwable cause) {
        super(cause);
    }

    public InflateException(String message) {
        super(message);
    }

    public InflateException(String message, Throwable cause) {
        super(message, cause);
    }
}
