package com.tyron.builder.api.internal.tasks.options;

import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.internal.typeconversion.TypeConversionException;
import com.tyron.builder.api.tasks.options.Option;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * An option with one or more values.
 */
public class MultipleValueOptionElement extends AbstractOptionElement {
    private final PropertySetter setter;
    private final NotationParser<CharSequence, ?> notationParser;

    public MultipleValueOptionElement(String optionName, Option option, Class<?> elementType, PropertySetter setter, OptionValueNotationParserFactory notationParserFactory) {
        super(optionName, option, List.class, setter.getDeclaringClass());
        this.setter = setter;
        this.notationParser = createNotationParserOrFail(notationParserFactory, optionName, elementType, setter.getDeclaringClass());
    }

    @Override
    public Set<String> getAvailableValues() {
        return Collections.emptySet();
    }

    @Override
    public void apply(Object object, List<String> parameterValues) throws TypeConversionException {
        List<Object> values = new ArrayList<Object>(parameterValues.size());
        for (String parameterValue : parameterValues) {
            values.add(notationParser.parseNotation(parameterValue));
        }
        setter.setValue(object, values);
    }
}
