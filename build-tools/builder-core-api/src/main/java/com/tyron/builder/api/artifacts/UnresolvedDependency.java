package com.tyron.builder.api.artifacts;

/**
 * Unsuccessfully resolved dependency.
 */
public interface UnresolvedDependency {
    /**
     * The module selector of the dependency.
     *
     * @since 1.1-rc-1
     */
    ModuleVersionSelector getSelector();

    /**
     * the exception that is the cause of unresolved state
     */
    Throwable getProblem();
}
