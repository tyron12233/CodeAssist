package com.tyron.builder.initialization;

import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.groovy.scripts.DefaultScript;

import groovy.lang.Closure;

public abstract class InitScript extends DefaultScript {
    public ScriptHandler getInitscript() {
        return getBuildscript();
    }

    public void initscript(Closure configureClosure) {
        buildscript(configureClosure);
    }

    public String toString() {
        return "initialization script";
    }
}
