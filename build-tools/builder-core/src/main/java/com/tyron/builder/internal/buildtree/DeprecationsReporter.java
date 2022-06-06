package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.deprecation.DeprecationLogger;
import com.tyron.builder.problems.buildtree.ProblemReporter;

import java.io.File;
import java.util.function.Consumer;

public class DeprecationsReporter implements ProblemReporter {
    @Override
    public String getId() {
        return "deprecations";
    }

    @Override
    public void report(File reportDir, Consumer<? super Throwable> validationFailures) {
        Throwable failure = DeprecationLogger.getDeprecationFailure();
        if (failure != null) {
            validationFailures.accept(failure);
        }
    }
}
