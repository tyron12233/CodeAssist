package com.tyron.builder.language.base.internal.compile;

import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.language.base.compile.CompilerVersion;

public class VersionAwareCompiler<T extends CompileSpec> implements Compiler<T> {

    private final CompilerVersion compilerVersion;
    private final Compiler<T> compiler;

    public VersionAwareCompiler(Compiler<T> compiler, CompilerVersion version) {
        this.compiler = compiler;
        this.compilerVersion = version;
    }

    @Override
    public WorkResult execute(T spec) {
        return compiler.execute(spec);
    }

    public CompilerVersion getVersion() {
        return compilerVersion;
    }

}
