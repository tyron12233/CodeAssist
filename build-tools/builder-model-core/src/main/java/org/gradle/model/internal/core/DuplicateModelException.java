package org.gradle.model.internal.core;

import org.gradle.api.GradleException;

// TODO generic model related super exception?
public class DuplicateModelException extends GradleException {

    public DuplicateModelException(String message) {
        super(message);
    }

}
