package com.tyron.builder.api.internal.tasks.options;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.JavaMethod;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.tasks.options.Option;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

public class MethodOptionElement {

    private static String assertValidOptionName(Option option, String elementName, Class<?> declaredClass) {
        if (option.option().length() == 0) {
            throw new OptionValidationException(String.format("No option name set on '%s' in class '%s'.", elementName, declaredClass.getName()));
        }
        return option.option();
    }

    public static OptionElement create(Option option, Method method, OptionValueNotationParserFactory optionValueNotationParserFactory) {
        String optionName = assertValidOptionName(option, method.getName(), method.getDeclaringClass());
        if (Property.class.isAssignableFrom(method.getReturnType())) {
            assertCanUseMethodReturnType(optionName, method);
            PropertySetter setter = mutateUsingReturnValue(method);
            return AbstractOptionElement.of(optionName, option, setter, optionValueNotationParserFactory);
        }
        if (method.getParameterTypes().length == 0) {
            return new BooleanOptionElement(optionName, option, setFlagUsingMethod(method));
        }

        assertCanUseMethodParam(optionName, method);
        PropertySetter setter = mutateUsingParameter(method);
        return AbstractOptionElement.of(optionName, option, setter, optionValueNotationParserFactory);
    }

    private static PropertySetter setFlagUsingMethod(final Method method) {
        return new MethodInvokingSetter(method);
    }

    private static PropertySetter mutateUsingParameter(Method method) {
        return new MethodPropertySetter(method);
    }

    private static PropertySetter mutateUsingReturnValue(Method method) {
        return new PropertyValueSetter(method);
    }

    private static void assertCanUseMethodReturnType(String optionName, Method method) {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 0) {
            throw new OptionValidationException(String.format("Option '%s' on method that returns %s cannot take parameters in class '%s#%s'.",
                    optionName, method.getGenericReturnType(), method.getDeclaringClass().getName(), method.getName()));
        }
    }

    private static void assertCanUseMethodParam(String optionName, Method method) {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length > 1) {
            throw new OptionValidationException(String.format("Option '%s' on method cannot take multiple parameters in class '%s#%s'.",
                    optionName, method.getDeclaringClass().getName(), method.getName()));
        }
    }

    private static class MethodPropertySetter implements PropertySetter {
        private final Method method;

        public MethodPropertySetter(Method method) {
            this.method = method;
        }

        @Override
        public Class<?> getDeclaringClass() {
            return method.getDeclaringClass();
        }

        @Override
        public Class<?> getRawType() {
            return method.getParameterTypes()[0];
        }

        @Override
        public Type getGenericType() {
            return method.getGenericParameterTypes()[0];
        }

        @Override
        public void setValue(Object target, Object value) {
            JavaMethod.of(Object.class, method).invoke(target, value);
        }
    }

    private static class PropertyValueSetter implements PropertySetter {
        private final Method method;
        private final Class<?> elementType;

        public PropertyValueSetter(Method method) {
            this.method = method;
            this.elementType = ModelType.of(method.getGenericReturnType()).getTypeVariables().get(0).getRawClass();
        }

        @Override
        public Class<?> getDeclaringClass() {
            return method.getDeclaringClass();
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
            Property<Object> property = Cast.uncheckedNonnullCast(JavaMethod.of(Object.class, method).invoke(target));
            property.set(value);
        }
    }

    private static class MethodInvokingSetter implements PropertySetter {
        private final Method method;

        public MethodInvokingSetter(Method method) {
            this.method = method;
        }

        @Override
        public Class<?> getDeclaringClass() {
            return method.getDeclaringClass();
        }

        @Override
        public Class<?> getRawType() {
            return Void.TYPE;
        }

        @Override
        public Type getGenericType() {
            return Void.TYPE;
        }

        @Override
        public void setValue(Object object, Object value) {
            JavaMethod.of(Object.class, method).invoke(object);
        }
    }
}
