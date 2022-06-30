package com.tyron.builder.internal.build;


/**
 * A build that is a child of some other build, and whose lifetime is bounded by the lifetime of that containing build.
 */
public interface NestedBuildState extends BuildState {
    /**
     * Runs any user build finished hooks and other user code cleanup for this build, if not already. Does not stop the services for this build.
     */
    ExecutionResult<Void> finishBuild();
}
