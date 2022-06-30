package com.tyron.builder.model.internal.core;

import com.tyron.builder.api.BuildException;

// TODO generic model related super exception?
public class DuplicateModelException extends BuildException {

    public DuplicateModelException(String message) {
        super(message);
    }

}
