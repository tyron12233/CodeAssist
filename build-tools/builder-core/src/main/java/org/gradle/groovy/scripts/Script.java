package org.gradle.groovy.scripts;

import org.gradle.internal.logging.StandardOutputCapture;
import org.gradle.internal.service.ServiceRegistry;

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
