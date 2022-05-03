package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.internal.scan.UsedByScanPlugin;

/**
 * A dependency that was resolved successfully.
 */
@UsedByScanPlugin
public interface ResolvedDependencyResult extends DependencyResult {
    /**
     * Returns the selected component. This may not necessarily be the same as the requested component. For example, a dynamic version
     * may have been requested, or the version may have been substituted due to conflict resolution, or by being forced, or for some other reason.
     */
    ResolvedComponentResult getSelected();

    /**
     * Returns the resolved variant for this dependency.
     *
     * @since 5.6
     */
    ResolvedVariantResult getResolvedVariant();
}
