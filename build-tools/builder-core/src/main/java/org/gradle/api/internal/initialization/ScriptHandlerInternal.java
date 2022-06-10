package org.gradle.api.internal.initialization;

import org.gradle.api.initialization.dsl.ScriptHandler;
import org.gradle.internal.classpath.ClassPath;

public interface ScriptHandlerInternal extends ScriptHandler {

    void addScriptClassPathDependency(Object notation);

    /**
     * @return The script classpath as used at runtime.
     */
    ClassPath getScriptClassPath();

    /**
     * @return The resolved non-instrumented script classpath.
     */
    ClassPath getNonInstrumentedScriptClassPath();
}
