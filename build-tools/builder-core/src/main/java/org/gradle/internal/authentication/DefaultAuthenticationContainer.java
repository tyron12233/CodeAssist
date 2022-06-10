package org.gradle.internal.authentication;

import org.gradle.api.artifacts.repositories.AuthenticationContainer;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.internal.DefaultPolymorphicDomainObjectContainer;
import org.gradle.authentication.Authentication;
import org.gradle.internal.reflect.Instantiator;

public class DefaultAuthenticationContainer extends DefaultPolymorphicDomainObjectContainer<Authentication> implements AuthenticationContainer {

    public DefaultAuthenticationContainer(Instantiator instantiator, CollectionCallbackActionDecorator callbackDecorator) {
        super(Authentication.class, instantiator, instantiator, callbackDecorator);
    }

}
