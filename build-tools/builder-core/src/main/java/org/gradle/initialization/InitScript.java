package org.gradle.initialization;

import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.groovy.scripts.DefaultScript;

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
