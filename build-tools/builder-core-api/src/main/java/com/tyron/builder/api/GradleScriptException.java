package com.tyron.builder.api;

import com.tyron.builder.internal.exceptions.Contextual;

/**
 * <p>A <code>GradleScriptException</code> is thrown when an exception occurs in the compilation or execution of a
 * script.</p>
 */
@Contextual
public class GradleScriptException extends BuildException {
    public GradleScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
