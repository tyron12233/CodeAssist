package com.tyron.builder.api.internal.reflect.service;

import java.lang.reflect.Type;

public class UnknownServiceException extends IllegalArgumentException {
    private final Type type;

    public UnknownServiceException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}