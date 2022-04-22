package com.tyron.builder.internal.reflect;

import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class MutableClassDetails implements ClassDetails {
    private final Class<?> type;
    private final MethodSet instanceMethods = new MethodSet();
    private final Map<String, MutablePropertyDetails> properties = new TreeMap<String, MutablePropertyDetails>();
    private final List<Method> methods = new ArrayList<Method>();
    private final List<Field> instanceFields = new ArrayList<>();
    private final Set<Class<?>> superTypes = new LinkedHashSet<Class<?>>();

    MutableClassDetails(Class<?> type) {
        this.type = type;
    }

    @Override
    public List<Method> getAllMethods() {
        return methods;
    }

    @Override
    public Collection<Method> getInstanceMethods() {
        return instanceMethods.getValues();
    }

    @Override
    public List<Field> getInstanceFields() {
        return instanceFields;
    }

    public Set<Class<?>> getSuperTypes() {
        return superTypes;
    }

    /*
     * Does a defensive copy to avoid leaking class references through the MutablePropertyDetails
     * contained in the maps values. The keyset would keep a strong reference back to the map
     * and all its entries.
     */
    @Override
    public Set<String> getPropertyNames() {
        return ImmutableSet.copyOf(properties.keySet());
    }

    @Override
    public Collection<? extends PropertyDetails> getProperties() {
        return properties.values();
    }

    @Override
    public PropertyDetails getProperty(String name) throws NoSuchPropertyException {
        MutablePropertyDetails property = properties.get(name);
        if (property == null) {
            throw new NoSuchPropertyException(String.format("No property '%s' found on %s.", name, type));
        }
        return property;
    }

    void superType(Class<?> type) {
        superTypes.add(type);
    }

    void method(Method method) {
        methods.add(method);
    }

    void instanceMethod(Method method) {
        instanceMethods.add(method);
    }

    MutablePropertyDetails property(String propertyName) {
        MutablePropertyDetails property = properties.get(propertyName);
        if (property == null) {
            property = new MutablePropertyDetails(propertyName);
            properties.put(propertyName, property);
        }
        return property;
    }

    public void field(Field field) {
        if (Modifier.isStatic(field.getModifiers())) {
            return;
        }
        MutablePropertyDetails property = properties.get(field.getName());
        if (property != null) {
            property.field(field);
        }
        instanceFields.add(field);
    }
}