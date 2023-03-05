package org.gradle.composite.internal;

import org.gradle.internal.serialize.DefaultSerializerRegistry;
import org.gradle.internal.snapshot.impl.ValueSnapshotterSerializerRegistry;

public class CompositeBuildsValueSnapshotterSerializerRegistry extends DefaultSerializerRegistry implements ValueSnapshotterSerializerRegistry {

    public CompositeBuildsValueSnapshotterSerializerRegistry() {
        super();
        register(CompositeProjectComponentArtifactMetadata.class, new CompositeProjectComponentArtifactMetadataSerializer());
    }
}
