package org.gradle.api.tasks.bundling.internal;

import org.gradle.api.GradleException;

public class Zip64RequiredException extends GradleException {

    public Zip64RequiredException(String message) {
        super(message);
    }

}
