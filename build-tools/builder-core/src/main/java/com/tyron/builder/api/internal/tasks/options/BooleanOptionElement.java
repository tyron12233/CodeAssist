package com.tyron.builder.api.internal.tasks.options;

import com.tyron.builder.internal.typeconversion.TypeConversionException;
import com.tyron.builder.api.tasks.options.Option;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A flag, does not take an argument.
 */
public class BooleanOptionElement extends AbstractOptionElement {
    private final PropertySetter setter;

    public BooleanOptionElement(String optionName, Option option, PropertySetter setter) {
        super(optionName, option, Void.TYPE, setter.getDeclaringClass());
        this.setter = setter;
    }

    @Override
    public Set<String> getAvailableValues() {
        return Collections.emptySet();
    }

    @Override
    public void apply(Object object, List<String> parameterValues) throws TypeConversionException {
        setter.setValue(object, Boolean.TRUE);
    }
}
