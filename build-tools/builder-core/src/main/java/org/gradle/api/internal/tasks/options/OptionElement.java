package org.gradle.api.internal.tasks.options;

import org.gradle.internal.typeconversion.TypeConversionException;

import java.util.List;
import java.util.Set;

public interface OptionElement {
    Set<String> getAvailableValues();

    Class<?> getOptionType();

    String getOptionName();

    /**
     * @throws TypeConversionException On failure to convert the supplied values to the appropriate target types.
     */
    void apply(Object object, List<String> parameterValues) throws TypeConversionException;

    String getDescription();
}

