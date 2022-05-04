package com.tyron.builder.groovy.scripts;

import com.tyron.builder.api.GradleScriptException;

/**
 * A {@code ScriptCompilationException} is thrown when a script cannot be compiled.
 */
//@UsedByScanPlugin
public class ScriptCompilationException extends GradleScriptException {
    private final ScriptSource scriptSource;
    private final Integer lineNumber;

    public ScriptCompilationException(String message, Throwable cause, ScriptSource scriptSource, Integer lineNumber) {
        super(message, cause);
        this.scriptSource = scriptSource;
        this.lineNumber = lineNumber;
    }

    public ScriptSource getScriptSource() {
        return scriptSource;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }
}
