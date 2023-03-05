package org.gradle.internal.build.event.types;

import org.gradle.tooling.internal.protocol.InternalFailure;

import java.io.Serializable;
import java.util.List;

public class AbstractTestFailure implements Serializable {

    private final String message;
    private final String description;
    private final List<? extends InternalFailure> causes;
    private final String className;
    private final String stacktrace;

    protected AbstractTestFailure(String message, String description, List<? extends InternalFailure> causes, String className, String stacktrace) {
        this.message = message;
        this.description = description;
        this.causes = causes;
        this.className = className;
        this.stacktrace = stacktrace;
    }

    public String getMessage() {
        return message;
    }

    public String getDescription() {
        return description;
    }

    public String getClassName() {
        return className;
    }

    public String getStacktrace() {
        return stacktrace;
    }

    public List<? extends InternalFailure> getCauses() {
        return causes;
    }
}