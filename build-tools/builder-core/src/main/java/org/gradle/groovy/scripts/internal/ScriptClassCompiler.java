package org.gradle.groovy.scripts.internal;

import org.gradle.api.Action;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import org.codehaus.groovy.ast.ClassNode;

import groovy.lang.Script;

@ServiceScope(Scopes.Build.class)
public interface ScriptClassCompiler {
    <T extends Script, M> CompiledScript<T, M> compile(ScriptSource source, ClassLoaderScope targetScope, CompileOperation<M> transformer, Class<T> scriptBaseClass, Action<? super ClassNode> verifier);
}
