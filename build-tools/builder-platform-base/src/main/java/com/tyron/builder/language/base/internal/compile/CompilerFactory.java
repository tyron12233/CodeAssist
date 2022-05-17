package com.tyron.builder.language.base.internal.compile;

public interface CompilerFactory<T extends CompileSpec> {
    Compiler<T> newCompiler(T spec);
}
