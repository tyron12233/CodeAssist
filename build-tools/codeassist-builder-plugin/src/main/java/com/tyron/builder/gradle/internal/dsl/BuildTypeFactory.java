package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.core.ComponentType;
import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.model.ObjectFactory;

/** Factory to create BuildType object using an {@link ObjectFactory} to add the DSL methods. */
public class BuildTypeFactory implements NamedDomainObjectFactory<BuildType> {

    @NonNull private final DslServices dslServices;
    @NonNull private final ComponentType componentType;

    public BuildTypeFactory(
            @NonNull DslServices dslServices, @NonNull ComponentType componentType) {
        this.dslServices = dslServices;
        this.componentType = componentType;
    }

    @NonNull
    @Override
    public BuildType create(@NonNull String name) {
        return dslServices.newDecoratedInstance(BuildType.class, name, dslServices, componentType);
    }
}
