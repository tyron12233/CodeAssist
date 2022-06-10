package org.gradle.model.internal.core;

import org.gradle.api.BuildException;

// TODO generic model related super exception?
public class DuplicateModelException extends BuildException {

    public DuplicateModelException(String message) {
        super(message);
    }

}
