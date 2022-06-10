package org.gradle.internal.work;

public class NoAvailableWorkerLeaseException extends RuntimeException {
    public NoAvailableWorkerLeaseException(String message) {
        super(message);
    }
}