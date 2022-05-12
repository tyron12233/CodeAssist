package com.tyron.builder.groovy.scripts.internal;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.classpath.ClassPath;

import org.codehaus.groovy.ast.ClassNode;

import java.io.File;

import groovy.lang.Script;

public interface ScriptCompilationHandler {

    void compileToDir(ScriptSource source, ClassLoader classLoader, File classesDir, File metadataDir, CompileOperation<?> transformer,
                      Class<? extends Script> scriptBaseClass, Action<? super ClassNode> verifier);

    <T extends Script, M> CompiledScript<T, M> loadFromDir(ScriptSource source, HashCode sourceHashCode, ClassLoaderScope targetScope, ClassPath scriptClassPath,
                                                           File metadataCacheDir, CompileOperation<M> transformer, Class<T> scriptBaseClass);
}
