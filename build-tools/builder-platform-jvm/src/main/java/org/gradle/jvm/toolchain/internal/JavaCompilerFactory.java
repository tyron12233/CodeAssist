package org.gradle.jvm.toolchain.internal;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.language.base.internal.compile.CompileSpec;
import org.gradle.language.base.internal.compile.Compiler;

/**
 * Creates Java compilers based on the provided compile options.
 */
@ServiceScope(Scopes.Project.class)
public interface JavaCompilerFactory {
    <T extends CompileSpec> Compiler<T> create(Class<T> type);
}
