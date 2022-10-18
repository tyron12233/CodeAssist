package org.gradle.api;

import org.gradle.api.Task;

public class CircularDependencyException extends RuntimeException {

    public CircularDependencyException() {

    }

    public CircularDependencyException(String message) {
        super(message);
    }

    public CircularDependencyException(Task first, Task second) {
        super("Circular dependency detected: " + first + " <-> " + second);
    }
}
