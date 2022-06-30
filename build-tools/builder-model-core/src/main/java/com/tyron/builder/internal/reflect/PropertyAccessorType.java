package com.tyron.builder.internal.reflect;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * Distinguishes "get" getters, "is" getters and setters from non-property methods.
 *
 * Generally follows the JavaBean conventions, with 2 exceptions: is methods can return `Boolean` (in addition to `boolean`) and setter methods can return non-void values.
 *
 * This is essentially a superset of the conventions supported by Java, Groovy and Kotlin.
 */
public enum PropertyAccessorType {
    IS_GETTER(2) {
        @Override
        public Class<?> propertyTypeFor(Method method) {
            return method.getReturnType();
        }

        @Override
        public Type genericPropertyTypeFor(Method method) {
            return method.getGenericReturnType();
        }
    },

    GET_GETTER(3) {
        @Override
        public Class<?> propertyTypeFor(Method method) {
            return method.getReturnType();
        }

        @Override
        public Type genericPropertyTypeFor(Method method) {
            return method.getGenericReturnType();
        }
    },

    SETTER(3) {
        @Override
        public Class<?> propertyTypeFor(Method method) {
            requireSingleParam(method);
            return method.getParameterTypes()[0];
        }

        @Override
        public Type genericPropertyTypeFor(Method method) {
            requireSingleParam(method);
            return method.getGenericParameterTypes()[0];
        }

        private void requireSingleParam(Method method) {
            if (!takesSingleParameter(method)) {
                throw new IllegalArgumentException("Setter method should take one parameter: " + method);
            }
        }
    };

    private final int prefixLength;

    PropertyAccessorType(int prefixLength) {
        this.prefixLength = prefixLength;
    }

    public String propertyNameFor(Method method) {
        return propertyNameFor(method.getName());
    }

    public String propertyNameFor(String methodName) {
        String methodNamePrefixRemoved = methodName.substring(prefixLength);
        return decapitalize(methodNamePrefixRemoved);
    }

    /**
     * Decapitalizes a given string according to the rule:
     * <ul>
     * <li>If the first or only character is Upper Case, it is made Lower Case
     * <li>UNLESS the second character is also Upper Case, when the String is
     * returned unchanged <eul>
     *
     * @param name -
     *            the String to decapitalize
     * @return the decapitalized version of the String
     */
    public static String decapitalize(String name) {

        if (name == null)
            return null;
        // The rule for decapitalize is that:
        // If the first letter of the string is Upper Case, make it lower case
        // UNLESS the second letter of the string is also Upper Case, in which case no
        // changes are made.
        if (name.length() == 0 || (name.length() > 1 && Character.isUpperCase(name.charAt(1)))) {
            return name;
        }

        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }


    public abstract Class<?> propertyTypeFor(Method method);

    public abstract Type genericPropertyTypeFor(Method method);

    @Nullable
    public static PropertyAccessorType of(Method method) {
        if (isStatic(method)) {
            return null;
        }
        String methodName = method.getName();
        if (!hasVoidReturnType(method) && takesNoParameter(method)) {
            if (isGetGetterName(methodName)) {
                return GET_GETTER;
            }
            // is method that returns Boolean is not a getter according to JavaBeans, but include it for compatibility with Groovy
            if (isIsGetterName(methodName) && (method.getReturnType().equals(Boolean.TYPE) || method.getReturnType().equals(Boolean.class))) {
                return IS_GETTER;
            }
        }
        if (takesSingleParameter(method) && isSetterName(methodName)) {
            return SETTER;
        }
        return null;
    }

    public static PropertyAccessorType fromName(String methodName) {
        if (isGetGetterName(methodName)) {
            return GET_GETTER;
        }
        if (isIsGetterName(methodName)) {
            return IS_GETTER;
        }
        if (isSetterName(methodName)) {
            return SETTER;
        }
        return null;
    }

    private static boolean isStatic(Method method) {
        return Modifier.isStatic(method.getModifiers());
    }

    public static boolean hasVoidReturnType(Method method) {
        return void.class.equals(method.getReturnType());
    }

    public static boolean takesNoParameter(Method method) {
        return method.getParameterTypes().length == 0;
    }

    public static boolean takesSingleParameter(Method method) {
        return method.getParameterCount() == 1;
    }

    private static boolean isGetGetterName(String methodName) {
        return methodName.startsWith("get") && methodName.length() > 3;
    }

    private static boolean isIsGetterName(String methodName) {
        return methodName.startsWith("is") && methodName.length() > 2;
    }

    private static boolean isSetterName(String methodName) {
        return methodName.startsWith("set") && methodName.length() > 3;
    }

    public static boolean hasGetter(Collection<PropertyAccessorType> accessorTypes) {
        return accessorTypes.contains(GET_GETTER) || accessorTypes.contains(IS_GETTER);
    }

    public static boolean hasSetter(Collection<PropertyAccessorType> accessorTypes) {
        return accessorTypes.contains(SETTER);
    }
}