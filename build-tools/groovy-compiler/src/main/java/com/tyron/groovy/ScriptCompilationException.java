package com.tyron.groovy;

public class ScriptCompilationException extends RuntimeException {
    public ScriptCompilationException(Throwable e) {
        super(e);
    }

    public ScriptCompilationException(String s) {
        super(s);
    }
}
