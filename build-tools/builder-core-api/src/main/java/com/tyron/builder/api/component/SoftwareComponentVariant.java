package com.tyron.builder.api.component;

import com.tyron.builder.api.Named;
import com.tyron.builder.api.artifacts.DependencyConstraint;
import com.tyron.builder.api.artifacts.ExcludeRule;
import com.tyron.builder.api.artifacts.ModuleDependency;
import com.tyron.builder.api.artifacts.PublishArtifact;
import com.tyron.builder.api.attributes.HasAttributes;
import com.tyron.builder.api.capabilities.Capability;

import java.util.Set;

/**
 * A software component variant, which has a number of artifacts,
 * dependencies, constraints and capabilities, and that can be
 * published to various formats (Gradle metadata, POM, ivy.xml, ...)
 *
 * @since 5.3
 */
public interface SoftwareComponentVariant extends HasAttributes, Named {
    Set<? extends PublishArtifact> getArtifacts();
    Set<? extends ModuleDependency> getDependencies();
    Set<? extends DependencyConstraint> getDependencyConstraints();
    Set<? extends Capability> getCapabilities();
    Set<ExcludeRule> getGlobalExcludes();
}
