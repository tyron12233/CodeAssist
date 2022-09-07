package com.tyron.builder.api.transform;

/**
 * An exception during the execution of a Transform.
 * @deprecated
 */
@Deprecated
public class TransformException extends Exception {

    public TransformException(Throwable throwable) {
        super(throwable);
    }

    public TransformException(String s) {
        super(s);
    }

    public TransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
