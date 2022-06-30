package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Buildable;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.file.FileCollection;

/**
 * A set of artifacts to be published.
 */
public interface PublishArtifactSet extends DomainObjectSet<PublishArtifact>, Buildable {
    FileCollection getFiles();
}
