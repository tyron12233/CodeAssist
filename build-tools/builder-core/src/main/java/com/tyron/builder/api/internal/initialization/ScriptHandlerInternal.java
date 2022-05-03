package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.internal.classpath.ClassPath;

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
