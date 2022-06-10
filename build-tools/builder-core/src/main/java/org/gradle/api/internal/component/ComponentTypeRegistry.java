package org.gradle.api.internal.component;

import org.gradle.api.component.Component;

public interface ComponentTypeRegistry {
    ComponentTypeRegistration maybeRegisterComponentType(Class<? extends Component> componentType);

    ComponentTypeRegistration getComponentRegistration(Class<? extends Component> componentType);
}
