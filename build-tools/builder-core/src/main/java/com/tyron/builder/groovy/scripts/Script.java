package com.tyron.builder.groovy.scripts;

import com.tyron.builder.internal.logging.StandardOutputCapture;
import com.tyron.builder.internal.service.ServiceRegistry;

/**
 * The base class for all scripts executed by Gradle.
 */
public abstract class Script extends groovy.lang.Script {
    private ScriptSource source;
    private ClassLoader contextClassloader;

    public ScriptSource getScriptSource() {
        return source;
    }

    public void setScriptSource(ScriptSource source) {
        this.source = source;
    }

    public void setContextClassloader(ClassLoader contextClassloader) {
        this.contextClassloader = contextClassloader;
    }

    public ClassLoader getContextClassloader() {
        return contextClassloader;
    }

    public abstract void init(Object target, ServiceRegistry services);

    public abstract StandardOutputCapture getStandardOutputCapture();
}
