package org.gradle.api.internal.artifacts;

import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.attributes.Attribute;

public abstract class ArtifactAttributes {
    /**
     * @deprecated use public {@link ArtifactTypeDefinition#ARTIFACT_TYPE_ATTRIBUTE} instead.
     */
    @Deprecated
    public static final Attribute<String> ARTIFACT_FORMAT = ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE;
}
