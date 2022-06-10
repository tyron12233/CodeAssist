package org.gradle.groovy.scripts;

import org.gradle.api.Action;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.groovy.scripts.internal.CompileOperation;

import org.codehaus.groovy.ast.ClassNode;

/**
 * Compiles a script into a {@code Script} object.
 */
public interface ScriptCompiler {

    /**
     * Compiles the script into a {@code Script} object of the given type.
     *
     * @return a {@code ScriptRunner} for the script.
     * @throws ScriptCompilationException On compilation failure.
     */
    <T extends Script, M> ScriptRunner<T, M> compile(Class<T> scriptType, CompileOperation<M> extractingTransformer, ClassLoaderScope targetScope, Action<? super ClassNode> verifier);
}
