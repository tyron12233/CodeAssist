package com.tyron.layoutpreview.convert;

/**
 * Thrown when something wrong has occurred when converting JSON to XML or vice versa
 */
public class ConvertException extends Exception {

    public ConvertException(String message) {
        super(message);
    }

    public ConvertException(String message, Throwable e) {
        super(message, e);
    }
}
