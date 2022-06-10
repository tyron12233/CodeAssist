package org.gradle.api.artifacts.repositories;

import org.gradle.api.PolymorphicDomainObjectContainer;
import org.gradle.authentication.Authentication;

/**
 * Container for configuring repository authentication schemes of type {@link org.gradle.authentication.Authentication}.
 */
public interface AuthenticationContainer extends PolymorphicDomainObjectContainer<Authentication> {
}
