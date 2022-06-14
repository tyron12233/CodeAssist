package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.language.base.internal.compile.CompileSpec;
import com.tyron.builder.language.base.internal.compile.Compiler;

/**
 * Creates Java compilers based on the provided compile options.
 */
@ServiceScope(Scopes.Project.class)
public interface JavaCompilerFactory {
    <T extends CompileSpec> Compiler<T> create(Class<T> type);
}
