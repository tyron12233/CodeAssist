package org.gradle.api.component;

import org.gradle.api.NamedDomainObjectSet;

/**
 * A Container that contains all of the Software Components produced by a Project.
 */
public interface SoftwareComponentContainer extends NamedDomainObjectSet<SoftwareComponent> {
}
