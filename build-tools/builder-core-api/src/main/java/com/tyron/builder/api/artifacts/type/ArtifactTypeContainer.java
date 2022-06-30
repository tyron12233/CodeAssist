package com.tyron.builder.api.artifacts.type;

import com.tyron.builder.api.NamedDomainObjectContainer;

/**
 * Defines a set of known artifact types and related meta-data. This allows you to fine tune how dependency resolution handles artifacts of a specific type.
 *
 * Each entry in this container defines a particular artifact type, such as a JAR or an AAR, and some information about that artifact type.
 *
 * @since 4.0
 */
public interface ArtifactTypeContainer extends NamedDomainObjectContainer<ArtifactTypeDefinition> {
}
