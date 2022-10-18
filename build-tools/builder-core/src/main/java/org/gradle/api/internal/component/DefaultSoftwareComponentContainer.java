package org.gradle.api.internal.component;

import org.gradle.api.component.SoftwareComponent;
import org.gradle.api.component.SoftwareComponentContainer;
import org.gradle.api.internal.DefaultNamedDomainObjectSet;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.internal.reflect.Instantiator;

public class DefaultSoftwareComponentContainer extends DefaultNamedDomainObjectSet<SoftwareComponent> implements SoftwareComponentContainer {
    public DefaultSoftwareComponentContainer(Instantiator instantiator, CollectionCallbackActionDecorator decorator) {
        super(SoftwareComponentInternal.class, instantiator, decorator);
    }
}
