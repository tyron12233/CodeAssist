package com.tyron.builder.internal.authentication;

import com.tyron.builder.api.artifacts.repositories.AuthenticationContainer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultPolymorphicDomainObjectContainer;
import com.tyron.builder.authentication.Authentication;
import com.tyron.builder.internal.reflect.Instantiator;

public class DefaultAuthenticationContainer extends DefaultPolymorphicDomainObjectContainer<Authentication> implements AuthenticationContainer {

    public DefaultAuthenticationContainer(Instantiator instantiator, CollectionCallbackActionDecorator callbackDecorator) {
        super(Authentication.class, instantiator, instantiator, callbackDecorator);
    }

}
