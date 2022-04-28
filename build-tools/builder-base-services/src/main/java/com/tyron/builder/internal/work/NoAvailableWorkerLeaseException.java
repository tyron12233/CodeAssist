package com.tyron.builder.internal.work;

public class NoAvailableWorkerLeaseException extends RuntimeException {
    public NoAvailableWorkerLeaseException(String message) {
        super(message);
    }
}