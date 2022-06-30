package com.tyron.builder.api.internal.component;

import com.tyron.builder.api.component.SoftwareComponent;
import com.tyron.builder.api.component.SoftwareComponentContainer;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectSet;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.internal.reflect.Instantiator;

public class DefaultSoftwareComponentContainer extends DefaultNamedDomainObjectSet<SoftwareComponent> implements SoftwareComponentContainer {
    public DefaultSoftwareComponentContainer(Instantiator instantiator, CollectionCallbackActionDecorator decorator) {
        super(SoftwareComponentInternal.class, instantiator, decorator);
    }
}
