package org.gradle.api.tasks.bundling.internal;

import org.gradle.api.BuildException;

public class Zip64RequiredException extends BuildException {

    public Zip64RequiredException(String message) {
        super(message);
    }

}
