package com.tyron.builder.internal.scripts;

import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

@EventScope(Scopes.Build.class)
public interface ScriptExecutionListener {
    void onScriptClassLoaded(ScriptSource source, Class<?> scriptClass);
}
