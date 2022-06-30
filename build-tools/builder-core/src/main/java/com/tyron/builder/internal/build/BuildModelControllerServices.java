package com.tyron.builder.internal.build;

import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

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