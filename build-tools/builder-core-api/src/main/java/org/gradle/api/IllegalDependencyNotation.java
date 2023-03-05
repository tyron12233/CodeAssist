package org.gradle.api;

/**
 * This exceptions is thrown, if a dependency is declared with a illegal notation.
 */
public class IllegalDependencyNotation extends GradleException {
    public IllegalDependencyNotation() {
    }

    public IllegalDependencyNotation(String message) {
        super(message);
    }

    public IllegalDependencyNotation(String message, Throwable cause) {
        super(message, cause);
    }
}
