package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.PolymorphicDomainObjectContainer;
import com.tyron.builder.authentication.Authentication;

/**
 * Container for configuring repository authentication schemes of type {@link com.tyron.builder.authentication.Authentication}.
 */
public interface AuthenticationContainer extends PolymorphicDomainObjectContainer<Authentication> {
}
