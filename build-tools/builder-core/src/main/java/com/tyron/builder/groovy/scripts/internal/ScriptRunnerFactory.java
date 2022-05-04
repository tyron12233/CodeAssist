package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.groovy.scripts.Script;
import com.tyron.builder.groovy.scripts.ScriptRunner;
import com.tyron.builder.groovy.scripts.ScriptSource;

public interface ScriptRunnerFactory {
    <T extends Script, M> ScriptRunner<T, M> create(CompiledScript<T, M> scriptClass, ScriptSource source, ClassLoader contextClassLoader);
}
