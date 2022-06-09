package com.tyron.builder.api;

/**
 * This exceptions is thrown, if a dependency is declared with a illegal notation.
 */
public class IllegalDependencyNotation extends BuildException {
    public IllegalDependencyNotation() {
    }

    public IllegalDependencyNotation(String message) {
        super(message);
    }

    public IllegalDependencyNotation(String message, Throwable cause) {
        super(message, cause);
    }
}
