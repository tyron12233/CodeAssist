package org.gradle.internal.scripts;

import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface ScriptExecutionListener {
    void onScriptClassLoaded(ScriptSource source, Class<?> scriptClass);
}
