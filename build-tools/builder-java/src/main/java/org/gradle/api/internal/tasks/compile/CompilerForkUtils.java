package org.gradle.api.internal.tasks.compile;

import org.gradle.api.tasks.TaskOutputs;
import org.gradle.api.tasks.compile.CompileOptions;

import static org.gradle.api.internal.lambdas.SerializableLambdas.spec;

public class CompilerForkUtils {
    public static void doNotCacheIfForkingViaExecutable(final CompileOptions compileOptions, TaskOutputs outputs) {
        outputs.doNotCacheIf(
            "Forking compiler via ForkOptions.executable",
            spec(element -> compileOptions.isFork() && compileOptions.getForkOptions().getExecutable() != null)
        );
    }
}
