package org.gradle.api.internal.tasks.options;

import org.gradle.internal.typeconversion.TypeConversionException;

import java.util.List;
import java.util.Set;

public interface OptionDescriptor extends Comparable<OptionDescriptor> {

    String getName();

    Class<?> getArgumentType();

    Set<String> getAvailableValues();

    String getDescription();

    /**
     * @throws TypeConversionException On failure to convert the given values to the required types.
     */
    void apply(Object object, List<String> values) throws TypeConversionException;
}

