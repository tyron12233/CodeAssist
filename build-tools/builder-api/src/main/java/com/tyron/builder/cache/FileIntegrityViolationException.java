package com.tyron.builder.cache;

/**
 * Indicates that the integrity of a file has been violated or cannot be guaranteed.
 */
public class FileIntegrityViolationException extends RuntimeException {

    public FileIntegrityViolationException(String message) {
        super(message);
    }
}