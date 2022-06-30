package com.tyron.builder.api.internal.tasks.properties;

import com.google.common.collect.ImmutableSortedSet;

public class GetInputPropertiesVisitor extends PropertyVisitor.Adapter {
    private final ImmutableSortedSet.Builder<InputPropertySpec> inputProperties = ImmutableSortedSet.naturalOrder();

    @Override
    public void visitInputProperty(String propertyName, PropertyValue value, boolean optional) {
        inputProperties.add(new DefaultInputPropertySpec(propertyName, value));
    }

    public ImmutableSortedSet<InputPropertySpec> getProperties() {
        return inputProperties.build();
    }
}