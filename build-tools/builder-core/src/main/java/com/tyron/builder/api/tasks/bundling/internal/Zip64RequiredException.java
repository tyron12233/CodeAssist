package com.tyron.builder.api.tasks.bundling.internal;

import com.tyron.builder.api.BuildException;

public class Zip64RequiredException extends BuildException {

    public Zip64RequiredException(String message) {
        super(message);
    }

}
