package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.ConfigurationVariant;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.internal.Factory;

import java.util.List;

public interface ConfigurationVariantInternal extends ConfigurationVariant {
    void artifactsProvider(Factory<List<PublishArtifact>> artifacts);
    void preventFurtherMutation();
}
