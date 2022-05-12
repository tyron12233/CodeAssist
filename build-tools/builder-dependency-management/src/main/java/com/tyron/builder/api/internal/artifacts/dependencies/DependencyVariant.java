package com.tyron.builder.api.internal.artifacts.dependencies;

import com.tyron.builder.api.artifacts.ModuleDependencyCapabilitiesHandler;
import com.tyron.builder.api.attributes.AttributeContainer;

import javax.annotation.Nullable;

public interface DependencyVariant {
    void mutateAttributes(AttributeContainer attributes);
    void mutateCapabilities(ModuleDependencyCapabilitiesHandler capabilitiesHandler);

    @Nullable
    String getClassifier();

    @Nullable
    String getArtifactType();
}
