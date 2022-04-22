package com.tyron.builder.language.base.internal.compile;

import com.tyron.builder.api.tasks.WorkResult;

public interface Compiler<T extends CompileSpec> {
    WorkResult execute(T spec);
}

