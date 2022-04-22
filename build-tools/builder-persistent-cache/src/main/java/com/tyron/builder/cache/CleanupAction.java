package com.tyron.builder.cache;

/**
 * An action that cleans up a {@link CleanableStore}.
 *
 * @see org.gradle.cache.internal.CompositeCleanupAction
 * @see CacheBuilder#withCleanup(CleanupAction)
 */
public interface CleanupAction {

    void clean(CleanableStore cleanableStore, CleanupProgressMonitor progressMonitor);

    CleanupAction NO_OP = new CleanupAction() {
        @Override
        public void clean(CleanableStore cleanableStore, CleanupProgressMonitor progressMonitor) {
            // no-op
        }
    };

}