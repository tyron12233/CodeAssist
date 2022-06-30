package com.tyron.builder.api.artifacts.result;

/**
 * A component that could not be resolved.
 *
 * @since 2.0
 */
public interface UnresolvedComponentResult extends ComponentResult {
    /**
     * Returns the failure that occurred when trying to resolve the component.
     */
    Throwable getFailure();
}
