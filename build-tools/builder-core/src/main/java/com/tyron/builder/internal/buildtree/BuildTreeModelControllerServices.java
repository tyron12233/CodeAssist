package com.tyron.builder.internal.buildtree;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.service.ServiceRegistration;

public interface BuildTreeModelControllerServices {
    /**
     * Creates a {@link Supplier} that will contribute services required for the model of a build tree with the given parameters.
     *
     * <p>Contributes the following services:</p>
     * <ul>
     *     <li>{@link org.gradle.api.internal.BuildType}</li>
     *     <li>{@link BuildModelParameters}</li>
     *     <li>{@link BuildActionModelRequirements}</li>
     * </ul>
     */
    Supplier servicesForBuildTree(BuildActionModelRequirements actionModelRequirements);

    /**
     * Creates a {@link Supplier} that will contribute the services required for the model of a nested build tree with the given parameters.
     *
     * <p>Contributes the same services as {@link #servicesForBuildTree(BuildActionModelRequirements)}.</p>
     */
    Supplier servicesForNestedBuildTree(StartParameterInternal startParameter);

    interface Supplier {
        void applyServicesTo(ServiceRegistration registration);
    }
}