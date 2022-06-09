package com.tyron.builder.internal.credentials;

import com.tyron.builder.api.credentials.HttpHeaderCredentials;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Optional;

public class DefaultHttpHeaderCredentials implements HttpHeaderCredentials {

    private String name;
    private String value;

    @Input
    @Optional
    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Internal
    @Override
    public String getValue() {
        return value;
    }

    @Override
    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return String.format("Credentials [header: %s]", name);
    }
}