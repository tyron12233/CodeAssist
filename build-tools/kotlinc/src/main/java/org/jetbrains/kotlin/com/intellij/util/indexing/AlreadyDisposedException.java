package org.jetbrains.kotlin.com.intellij.util.indexing;

public class AlreadyDisposedException extends RuntimeException {
    public AlreadyDisposedException(String message) {
        super(message);
    }
}
