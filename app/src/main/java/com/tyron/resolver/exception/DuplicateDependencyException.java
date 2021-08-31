package com.tyron.resolver.exception;

public class DuplicateDependencyException extends Exception {

    public DuplicateDependencyException() {
        super();
    }

    public DuplicateDependencyException(String message) {
        super(message);
    }

    public DuplicateDependencyException(String message, Throwable cause) {
        super(message, cause);
    }

    public DuplicateDependencyException(Throwable cause) {
        super(cause);
    }
}
