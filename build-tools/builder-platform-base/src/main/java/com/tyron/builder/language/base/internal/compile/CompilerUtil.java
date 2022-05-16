package com.tyron.builder.language.base.internal.compile;

public class CompilerUtil {
    @SuppressWarnings("unchecked")
    public static <T extends CompileSpec> Compiler<T> castCompiler(Compiler<?> compiler) {
        return (Compiler<T>) compiler;
    }
}
