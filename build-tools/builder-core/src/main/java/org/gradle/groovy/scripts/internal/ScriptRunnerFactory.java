package org.gradle.groovy.scripts.internal;

import org.gradle.groovy.scripts.Script;
import org.gradle.groovy.scripts.ScriptRunner;
import org.gradle.groovy.scripts.ScriptSource;

public interface ScriptRunnerFactory {
    <T extends Script, M> ScriptRunner<T, M> create(CompiledScript<T, M> scriptClass, ScriptSource source, ClassLoader contextClassLoader);
}
