package org.gradle.composite.internal;

import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.result.ComponentIdentifierSerializer;
import org.gradle.api.internal.artifacts.metadata.PublishArtifactLocalArtifactMetadataSerializer;
import org.gradle.internal.component.local.model.PublishArtifactLocalArtifactMetadata;
import org.gradle.internal.serialize.Decoder;
import org.gradle.internal.serialize.Encoder;
import org.gradle.internal.serialize.Serializer;

import java.io.File;

public class CompositeProjectComponentArtifactMetadataSerializer implements Serializer<CompositeProjectComponentArtifactMetadata> {

    private final ComponentIdentifierSerializer componentIdentifierSerializer = new ComponentIdentifierSerializer();
    private final PublishArtifactLocalArtifactMetadataSerializer publishArtifactLocalArtifactMetadataSerializer = new PublishArtifactLocalArtifactMetadataSerializer(componentIdentifierSerializer);

    @Override
    public CompositeProjectComponentArtifactMetadata read(Decoder decoder) throws Exception {
        ProjectComponentIdentifier componentIdentifier = (ProjectComponentIdentifier) componentIdentifierSerializer.read(decoder);
        PublishArtifactLocalArtifactMetadata delegate = publishArtifactLocalArtifactMetadataSerializer.read(decoder);
        File file = new File(decoder.readString());
        return new CompositeProjectComponentArtifactMetadata(componentIdentifier, delegate, file);
    }

    @Override
    public void write(Encoder encoder, CompositeProjectComponentArtifactMetadata value) throws Exception {
        componentIdentifierSerializer.write(encoder, value.getComponentIdentifier());
        publishArtifactLocalArtifactMetadataSerializer.write(encoder, (PublishArtifactLocalArtifactMetadata) value.getDelegate());
        encoder.writeString(value.getFile().getCanonicalPath());
    }
}
