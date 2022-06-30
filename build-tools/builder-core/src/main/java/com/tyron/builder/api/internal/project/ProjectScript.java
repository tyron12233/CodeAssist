package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.LoggingManager;
import com.tyron.builder.internal.logging.StandardOutputCapture;
import com.tyron.builder.plugin.use.internal.PluginsAwareScript;

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
