package com.tyron.builder.api.internal;

public class NullNamingPropertyException extends RuntimeException {
    NullNamingPropertyException(Object thing) {
        super(String.format("Unable to determine the name of '%s' because its value for the naming property 'name' is null", thing));
    }
}
