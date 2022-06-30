package com.tyron.builder.api.internal.component;

import com.tyron.builder.api.component.Component;

public interface ComponentTypeRegistry {
    ComponentTypeRegistration maybeRegisterComponentType(Class<? extends Component> componentType);

    ComponentTypeRegistration getComponentRegistration(Class<? extends Component> componentType);
}
