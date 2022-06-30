package com.tyron.builder.problems.buildtree;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.io.File;
import java.util.function.Consumer;

/**
 * Notifies the user of problems of some type collected during the execution of a build against a build tree.
 */
@ServiceScope(Scopes.BuildTree.class)
public interface ProblemReporter {
    /**
     * A stable identifier for this reporter. Reporters are ordered by id before {@link #report(File, Consumer)} is called, so
     * that the output is generated in a stable order rather than in an order based on the order that implementations
     * are discovered.
     */
    String getId();

    /**
     * Notifies the build user of whatever problems have been collected. May report problems to the console, or generate a report
     * or fail with one or more exceptions, or all of the above.
     *
     * @param reportDir The base directory that reports can be generated into.
     * @param validationFailures Collects any validation failures.
     */
    void report(File reportDir, Consumer<? super Throwable> validationFailures);
}
