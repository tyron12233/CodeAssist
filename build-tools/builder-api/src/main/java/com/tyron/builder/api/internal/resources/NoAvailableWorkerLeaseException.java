package com.tyron.builder.api.internal.resources;

public class NoAvailableWorkerLeaseException extends RuntimeException {
    public NoAvailableWorkerLeaseException(String message) {
        super(message);
    }
}