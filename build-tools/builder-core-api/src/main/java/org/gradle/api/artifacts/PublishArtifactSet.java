package org.gradle.api.artifacts;

import org.gradle.api.Buildable;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.file.FileCollection;

/**
 * A set of artifacts to be published.
 */
public interface PublishArtifactSet extends DomainObjectSet<PublishArtifact>, Buildable {
    FileCollection getFiles();
}
