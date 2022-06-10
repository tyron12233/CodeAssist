package org.gradle.internal.build;

import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;

/**
 * Contributes build scoped services.
 */
@ServiceScope(Scopes.BuildTree.class)
public interface BuildModelControllerServices {
    /**
     * Makes the following services available:
     *
     * <ul>
     *     <li>{@link BuildLifecycleController}</li>
     *     <li>{@link BuildState}</li>
     *     <li>{@link BuildDefinition}</li>
     *     <li>{@link GradleInternal}</li>
     * </ul>
     */
    Supplier servicesForBuild(BuildDefinition buildDefinition, BuildState owner, @Nullable BuildState parentBuild);

    interface Supplier {
        void applyServicesTo(ServiceRegistration registration, BuildScopeServices services);
    }
}