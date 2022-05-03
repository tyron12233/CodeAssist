package com.tyron.builder.api.internal;

public class NoNamingPropertyException extends RuntimeException {
    NoNamingPropertyException(Object thing) {
        super(String.format("Unable to determine the name of '%s' because it does not have a 'name' property", thing));
    }
}
