package com.tyron.builder.api.internal.tasks.options;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.options.Option;

import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

public class FieldOptionElement {

    public static OptionElement create(Option option, Field field, OptionValueNotationParserFactory optionValueNotationParserFactory) {
        String optionName = calOptionName(option, field);
        Class<?> fieldType = field.getType();

        if (Property.class.isAssignableFrom(fieldType)) {
            PropertySetter setter = mutateUsingGetter(field);
            return AbstractOptionElement.of(optionName, option, setter, optionValueNotationParserFactory);
        }

        PropertySetter setter = mutateUsingSetter(field);
        return AbstractOptionElement.of(optionName, option, setter, optionValueNotationParserFactory);
    }

    private static PropertySetter mutateUsingSetter(Field field) {
        return new FieldSetter(getSetter(field), field);
    }

    private static PropertySetter mutateUsingGetter(final Field field) {
        return new PropertyFieldSetter(getGetter(field), field);
    }

    private static String calOptionName(Option option, Field field) {
        if (option.option().length() == 0) {
            return field.getName();
        } else {
            return option.option();
        }
    }

    private static Method getSetter(Field field) {
        try {
            String setterName = "set" + StringUtils.capitalize(field.getName());
            return field.getDeclaringClass().getMethod(setterName, field.getType());
        } catch (NoSuchMethodException e) {
            throw new OptionValidationException(String.format("No setter for Option annotated field '%s' in class '%s'.",
                    field.getName(), field.getDeclaringClass()));
        }
    }

    private static Method getGetter(Field field) {
        try {
            String getterName = "get" + StringUtils.capitalize(field.getName());
            return field.getDeclaringClass().getMethod(getterName);
        } catch (NoSuchMethodException e) {
            throw new OptionValidationException(String.format("No getter for Option annotated field '%s' in class '%s'.",
                    field.getName(), field.getDeclaringClass()));
        }
    }

    private static class FieldSetter implements PropertySetter {
        private final Method setter;
        private final Field field;

        public FieldSetter(Method setter, Field field) {
            this.setter = setter;
            this.field = field;
        }

        @Override
        public Class<?> getDeclaringClass() {
            return field.getDeclaringClass();
        }

        @Override
        public Class<?> getRawType() {
            return setter.getParameterTypes()[0];
        }

        @Override
        public Type getGenericType() {
            return setter.getGenericParameterTypes()[0];
        }

        @Override
        public void setValue(Object target, Object value) {
            JavaMethod.of(Object.class, setter).invoke(target, value);
        }
    }

    private static class PropertyFieldSetter implements PropertySetter {
        private final Method getter;
        private final Field field;
        private final Class<?> elementType;

        public PropertyFieldSetter(Method getter, Field field) {
            this.getter = getter;
            this.field = field;
            this.elementType = ModelType.of(getter.getGenericReturnType()).getTypeVariables().get(0).getRawClass();
        }

        @Override
        public Class<?> getDeclaringClass() {
            return field.getDeclaringClass();
        }

        @Override
        public Class<?> getRawType() {
            return elementType;
        }

        @Override
        public Type getGenericType() {
            return elementType;
        }

        @Override
        public void setValue(Object target, Object value) {
            Property<Object> property = Cast.uncheckedNonnullCast(JavaMethod.of(Object.class, getter).invoke(target));
            property.set(value);
        }
    }
}
