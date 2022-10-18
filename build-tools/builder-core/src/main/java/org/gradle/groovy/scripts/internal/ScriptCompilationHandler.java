package org.gradle.groovy.scripts.internal;

import com.google.common.hash.HashCode;
import org.gradle.api.Action;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.classpath.ClassPath;

import org.codehaus.groovy.ast.ClassNode;

import java.io.File;

import groovy.lang.Script;

public interface ScriptCompilationHandler {

    void compileToDir(ScriptSource source, ClassLoader classLoader, File classesDir, File metadataDir, CompileOperation<?> transformer,
                      Class<? extends Script> scriptBaseClass, Action<? super ClassNode> verifier);

    <T extends Script, M> CompiledScript<T, M> loadFromDir(ScriptSource source, HashCode sourceHashCode, ClassLoaderScope targetScope, ClassPath scriptClassPath,
                                                           File metadataCacheDir, CompileOperation<M> transformer, Class<T> scriptBaseClass);
}
