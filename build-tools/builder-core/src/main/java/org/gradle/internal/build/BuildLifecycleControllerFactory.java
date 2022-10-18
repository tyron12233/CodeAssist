package org.gradle.internal.build;

import org.gradle.api.internal.BuildDefinition;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

/**
 * <p>Responsible for creating a {@link BuildLifecycleController} instance for a build.
 *
 * Caller must call {@link BuildLifecycleController#stop()} when finished with the launcher.
 */
@ServiceScope(Scopes.BuildTree.class)
public interface BuildLifecycleControllerFactory {
    BuildLifecycleController newInstance(BuildDefinition buildDefinition, BuildScopeServices buildScopeServices);
}
