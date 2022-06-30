package com.tyron.builder.internal.reflect;


import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

class MutablePropertyDetails implements PropertyDetails {
    private final String name;
    private final MethodSet getters = new MethodSet();
    private final MethodSet setters = new MethodSet();
    private Field field;

    MutablePropertyDetails(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Collection<Method> getGetters() {
        return getters.getValues();
    }

    @Override
    public Collection<Method> getSetters() {
        return setters.getValues();
    }

    @Nullable
    @Override
    public Field getBackingField() {
        return field;
    }

    void addGetter(Method method) {
        getters.add(method);
    }

    void addSetter(Method method) {
        setters.add(method);
    }

    void field(Field field) {
        if (!getters.isEmpty()) {
            this.field = field;
        }
    }
}