package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.tasks.TaskOutputs;
import com.tyron.builder.api.tasks.compile.CompileOptions;

import static com.tyron.builder.api.internal.lambdas.SerializableLambdas.spec;

public class CompilerForkUtils {
    public static void doNotCacheIfForkingViaExecutable(final CompileOptions compileOptions, TaskOutputs outputs) {
        outputs.doNotCacheIf(
            "Forking compiler via ForkOptions.executable",
            spec(element -> compileOptions.isFork() && compileOptions.getForkOptions().getExecutable() != null)
        );
    }
}
