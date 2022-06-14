package com.tyron.builder.api.internal.component;

import com.google.common.collect.Maps;
import com.tyron.builder.api.component.Artifact;
import com.tyron.builder.api.component.Component;

import java.util.Map;

public class DefaultComponentTypeRegistry implements ComponentTypeRegistry {
    private final Map<Class<? extends Component>, ComponentTypeRegistration> componentRegistrations = Maps.newHashMap();

    @Override
    public ComponentTypeRegistration maybeRegisterComponentType(Class<? extends Component> componentType) {
        ComponentTypeRegistration registration = componentRegistrations.get(componentType);
        if (registration == null) {
            registration = new DefaultComponentTypeRegistration(componentType);
            componentRegistrations.put(componentType, registration);
        }
        return registration;
    }

    @Override
    public ComponentTypeRegistration getComponentRegistration(Class<? extends Component> componentType) {
        ComponentTypeRegistration registration = componentRegistrations.get(componentType);
        if (registration == null) {
            throw new IllegalArgumentException(String.format("Not a registered component type: %s.", componentType.getName()));
        }
        return registration;
    }

    private static class DefaultComponentTypeRegistration implements ComponentTypeRegistration {
        private final Class<? extends Component> componentType;
        private final Map<Class<? extends Artifact>, ArtifactType> typeRegistrations = Maps.newHashMap();

        private DefaultComponentTypeRegistration(Class<? extends Component> componentType) {
            this.componentType = componentType;
        }

        @Override
        public ArtifactType getArtifactType(Class<? extends Artifact> artifact) {
            ArtifactType type = typeRegistrations.get(artifact);
            if (type == null) {
                throw new IllegalArgumentException(String.format("Artifact type %s is not registered for component type %s.", artifact.getName(), componentType.getName()));
            }
            return type;
        }

        @Override
        public ComponentTypeRegistration registerArtifactType(Class<? extends Artifact> artifact, ArtifactType artifactType) {
            if (typeRegistrations.containsKey(artifact)) {
                throw new IllegalStateException(String.format("Artifact type %s is already registered for component type %s.", artifact.getName(), componentType.getName()));
            }
            typeRegistrations.put(artifact, artifactType);
            return this;
        }
    }
}
