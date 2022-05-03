package com.tyron.builder.groovy.scripts.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import org.codehaus.groovy.ast.ClassNode;

import groovy.lang.Script;

@ServiceScope(Scopes.Build.class)
public interface ScriptClassCompiler {
    <T extends Script, M> CompiledScript<T, M> compile(ScriptSource source, ClassLoaderScope targetScope, CompileOperation<M> transformer, Class<T> scriptBaseClass, Action<? super ClassNode> verifier);
}
