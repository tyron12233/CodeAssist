package org.gradle.api.internal.artifacts;

import org.gradle.api.artifacts.ConfigurationVariant;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.internal.Factory;

import java.util.List;

public interface ConfigurationVariantInternal extends ConfigurationVariant {
    void artifactsProvider(Factory<List<PublishArtifact>> artifacts);
    void preventFurtherMutation();
    void setDescription(String description);
}
