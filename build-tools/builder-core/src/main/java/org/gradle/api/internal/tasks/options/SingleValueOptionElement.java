package org.gradle.api.internal.tasks.options;

import org.gradle.internal.typeconversion.NotationParser;
import org.gradle.internal.typeconversion.TypeConversionException;
import org.gradle.api.tasks.options.Option;
import org.gradle.internal.exceptions.ValueCollectingDiagnosticsVisitor;

import java.util.List;
import java.util.Set;

/**
 * An option with a single argument.
 */
public class SingleValueOptionElement extends AbstractOptionElement {
    private final PropertySetter setter;
    private final NotationParser<CharSequence, ?> notationParser;

    public SingleValueOptionElement(String optionName, Option option, Class<?> optionType, PropertySetter setter, OptionValueNotationParserFactory notationParserFactory) {
        super(optionName, option, String.class, setter.getDeclaringClass());
        this.setter = setter;
        notationParser = createNotationParserOrFail(notationParserFactory, optionName, optionType, setter.getDeclaringClass());
    }

    @Override
    public Set<String> getAvailableValues() {
        ValueCollectingDiagnosticsVisitor visitor = new ValueCollectingDiagnosticsVisitor();
        notationParser.describe(visitor);
        return visitor.getValues();
    }

    @Override
    public void apply(Object object, List<String> parameterValues) throws TypeConversionException {
        Object arg = notationParser.parseNotation(parameterValues.get(0));
        setter.setValue(object, arg);
    }
}


