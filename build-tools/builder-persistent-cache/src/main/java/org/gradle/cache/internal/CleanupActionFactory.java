package org.gradle.cache.internal;

import org.gradle.cache.CleanableStore;
import org.gradle.cache.CleanupAction;
import org.gradle.cache.CleanupProgressMonitor;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.RunnableBuildOperation;

public class CleanupActionFactory {
    private final BuildOperationExecutor buildOperationExecutor;

    public CleanupActionFactory(BuildOperationExecutor buildOperationExecutor) {
        this.buildOperationExecutor = buildOperationExecutor;
    }

    public CleanupAction create(CleanupAction action) {
        return new BuildOperationCacheCleanupDecorator(action, buildOperationExecutor);
    }

    private static class BuildOperationCacheCleanupDecorator implements CleanupAction {
        private final BuildOperationExecutor buildOperationExecutor;
        private final CleanupAction delegate;

        public BuildOperationCacheCleanupDecorator(CleanupAction delegate, BuildOperationExecutor buildOperationExecutor) {
            this.buildOperationExecutor = buildOperationExecutor;
            this.delegate = delegate;
        }

        @Override
        public void clean(final CleanableStore persistentCache, final CleanupProgressMonitor progressMonitor) {
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) {
                    delegate.clean(persistentCache, progressMonitor);
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Clean up " + persistentCache);
                }
            });
        }
    }
}
