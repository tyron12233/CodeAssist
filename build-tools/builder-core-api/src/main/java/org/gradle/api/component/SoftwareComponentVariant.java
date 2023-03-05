package org.gradle.api.component;

import org.gradle.api.Named;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.attributes.HasAttributes;
import org.gradle.api.capabilities.Capability;

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
