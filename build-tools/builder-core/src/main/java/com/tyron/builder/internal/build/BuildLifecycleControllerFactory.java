package com.tyron.builder.internal.build;

import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * <p>Responsible for creating a {@link BuildLifecycleController} instance for a build.
 *
 * Caller must call {@link BuildLifecycleController#stop()} when finished with the launcher.
 */
@ServiceScope(Scopes.BuildTree.class)
public interface BuildLifecycleControllerFactory {
    BuildLifecycleController newInstance(BuildDefinition buildDefinition, BuildScopeServices buildScopeServices);
}
