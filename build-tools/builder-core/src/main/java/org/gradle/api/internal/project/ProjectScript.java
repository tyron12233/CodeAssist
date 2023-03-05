package org.gradle.api.internal.project;

import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.LoggingManager;
import org.gradle.internal.logging.StandardOutputCapture;
import org.gradle.plugin.use.internal.PluginsAwareScript;

import java.util.Map;

import groovy.lang.Closure;

public abstract class ProjectScript extends PluginsAwareScript {

    @Override
    public void apply(Closure closure) {
        getScriptTarget().apply(closure);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(Map options) {
        getScriptTarget().apply(options);
    }

    @Override
    public ScriptHandler getBuildscript() {
        return getScriptTarget().getBuildscript();
    }

    @Override
    public void buildscript(Closure configureClosure) {
        getScriptTarget().buildscript(configureClosure);
    }

    @Override
    public StandardOutputCapture getStandardOutputCapture() {
        return getScriptTarget().getStandardOutputCapture();
    }

    @Override
    public LoggingManager getLogging() {
        return getScriptTarget().getLogging();
    }

    @Override
    public Logger getLogger() {
        return getScriptTarget().getLogger();
    }

    public String toString() {
        return getScriptTarget().toString();
    }

    @Override
    public ProjectInternal getScriptTarget() {
        return (ProjectInternal) super.getScriptTarget();
    }
}
