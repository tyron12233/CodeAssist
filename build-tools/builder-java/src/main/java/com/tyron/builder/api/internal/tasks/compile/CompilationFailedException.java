package com.tyron.builder.api.internal.tasks.compile;

import java.util.Locale;

public class CompilationFailedException extends RuntimeException {
    public CompilationFailedException() {
        super("Compilation failed; see the compiler error output for details.");
    }

    public CompilationFailedException(int exitCode) {
        super(String.format(Locale.ENGLISH, "Compilation failed with exit code %d; see the compiler error output for details.", exitCode));
    }

    public CompilationFailedException(Throwable cause) {
        super(cause);
    }
}