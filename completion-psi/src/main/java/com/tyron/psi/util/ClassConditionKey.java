package com.tyron.psi.util;

/**
 * @author peter
 */
public final class ClassConditionKey<T> {
    private final Class<T> myConditionClass;

    private ClassConditionKey(Class<T> aClass) {
        myConditionClass = aClass;
    }

    public static <T> ClassConditionKey<T> create(Class<T> aClass) {
        return new ClassConditionKey<>(aClass);
    }

    public boolean isInstance(Object o) {
        return myConditionClass.isInstance(o);
    }

    @Override
    public String toString() {
        return myConditionClass.getName();
    }
}
