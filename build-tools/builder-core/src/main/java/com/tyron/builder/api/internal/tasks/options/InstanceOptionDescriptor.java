package com.tyron.builder.api.internal.tasks.options;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.util.internal.CollectionUtils;

import java.util.Collection;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class InstanceOptionDescriptor implements OptionDescriptor {

    private final Object object;
    private final OptionElement optionElement;
    private final JavaMethod<Object, Collection> optionValueMethod;

    InstanceOptionDescriptor(Object object, OptionElement optionElement) {
        this(object, optionElement, null);
    }

    public InstanceOptionDescriptor(Object object, OptionElement optionElement, JavaMethod<Object, Collection> optionValueMethod) {
        this.object = object;
        this.optionElement = optionElement;
        this.optionValueMethod = optionValueMethod;
    }

    @Override
    public String getName() {
        return optionElement.getOptionName();
    }

    @Override
    public Set<String> getAvailableValues() {
        final Set<String> values = optionElement.getAvailableValues();

        if (getArgumentType().isAssignableFrom(String.class)) {
            values.addAll(readDynamicAvailableValues());
        }
        return values;
    }

    @Override
    public Class<?> getArgumentType() {
        return optionElement.getOptionType();
    }

    private List<String> readDynamicAvailableValues() {
        if (optionValueMethod != null) {
            Collection values = optionValueMethod.invoke(object);
            return CollectionUtils.collect(values, new LinkedList<>(), new Transformer<String, Object>() {
                @Override
                public String transform(Object o) {
                    return o.toString();
                }
            });
        }
        return Collections.emptyList();
    }

    @Override
    public String getDescription() {
        return optionElement.getDescription();
    }

    @Override
    public void apply(Object objectParam, List<String> parameterValues) {
        if (objectParam != object) {
            throw new AssertionError(String.format("Object %s not applyable. Expecting %s", objectParam, object));
        }
        optionElement.apply(objectParam, parameterValues);
    }

    @Override
    public int compareTo(OptionDescriptor o) {
        return getName().compareTo(o.getName());
    }
}
