package com.tyron.builder.api.internal.tasks.options;

import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.internal.typeconversion.NotationParser;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.api.tasks.options.Option;

import java.lang.annotation.IncompleteAnnotationException;
import java.lang.reflect.Method;
import java.util.List;

abstract class AbstractOptionElement implements OptionElement {
    private final String optionName;
    private final String description;
    private final Class<?> optionType;

    public AbstractOptionElement(String optionName, Option option, Class<?> optionType, Class<?> declaringClass) {
        this(readDescription(option, optionName, declaringClass), optionName, optionType);
    }

    private AbstractOptionElement(String description, String optionName, Class<?> optionType) {
        this.description = description;
        this.optionName = optionName;
        this.optionType = optionType;
    }

    @Override
    public Class<?> getOptionType() {
        return optionType;
    }

    public static OptionElement of(String optionName, Option option, PropertySetter setter, OptionValueNotationParserFactory notationParserFactory) {
        if (setter.getRawType().equals(Boolean.class) || setter.getRawType().equals(Boolean.TYPE)) {
            return new BooleanOptionElement(optionName, option, setter);
        }
        if (setter.getRawType().equals(List.class)) {
            Class<?> elementType = ModelType.of(setter.getGenericType()).getTypeVariables().get(0).getRawClass();
                return new MultipleValueOptionElement(optionName, option, elementType, setter, notationParserFactory);
        }
        return new SingleValueOptionElement(optionName, option, setter.getRawType(), setter, notationParserFactory);
    }

    private static String readDescription(Option option, String optionName, Class<?> declaringClass) {
        try {
            return option.description();
        } catch (IncompleteAnnotationException ex) {
            throw new OptionValidationException(String.format("No description set on option '%s' at for class '%s'.", optionName, declaringClass.getName()));
        }
    }

    protected Object invokeMethod(Object object, Method method, Object... parameterValues) {
        final JavaMethod<Object, Object> javaMethod = JavaMethod.of(Object.class, method);
        return javaMethod.invoke(object, parameterValues);
    }

    @Override
    public String getOptionName() {
        return optionName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    protected static <T> NotationParser<CharSequence, T> createNotationParserOrFail(OptionValueNotationParserFactory optionValueNotationParserFactory, String optionName, Class<T> optionType, Class<?> declaringClass) {
        try {
            return optionValueNotationParserFactory.toComposite(optionType);
        } catch (OptionValidationException ex) {
            throw new OptionValidationException(String.format("Option '%s' cannot be cast to type '%s' in class '%s'.",
                    optionName, optionType.getName(), declaringClass.getName()));
        }
    }

    protected static Class<?> calculateOptionType(Class<?> type) {
        //we don't want to support "--flag true" syntax
        if (type == Boolean.class || type == Boolean.TYPE) {
            return Void.TYPE;
        } else {
            return type;
        }
    }
}
