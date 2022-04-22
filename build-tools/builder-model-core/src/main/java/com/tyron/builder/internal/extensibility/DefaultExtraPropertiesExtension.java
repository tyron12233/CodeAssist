package com.tyron.builder.internal.extensibility;

import com.tyron.builder.api.plugins.ExtraPropertiesExtension;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

public class DefaultExtraPropertiesExtension implements ExtraPropertiesExtension {

    private final Map<String, Object> storage = new HashMap<>();

    @Override
    public boolean has(String name) {
        return storage.containsKey(name);
    }

    @Override
    @Nullable
    public Object get(String name) {
        Object value = find(name);
        if (value == null && !has(name)) {
            throw new UnknownPropertyException(this, name);
        }
        return value;
    }

    @Nullable
    public Object find(String name) {
        return storage.get(name);
    }

    @Override
    public void set(String name, @Nullable Object value) {
        storage.put(name, value);
    }

//    @Override
//    @Nullable
//    public Object getProperty(String name) {
//        if (name.equals("properties")) {
//            return getProperties();
//        }
//
//        if (storage.containsKey(name)) {
//            return storage.get(name);
//        } else {
//            throw new MissingPropertyException(UnknownPropertyException.createMessage(name), name, null);
//        }
//    }
//
//    @Override
//    public void setProperty(String name, @Nullable Object newValue) {
//        if (name.equals("properties")) {
//            throw new ReadOnlyPropertyException("name", ExtraPropertiesExtension.class);
//        }
//        set(name, newValue);
//    }

    @Override
    public Map<String, Object> getProperties() {
        return new HashMap<>(storage);
    }

//    public Object methodMissing(String name, Object args) {
//        Object item = find(name);
//        if (item instanceof Closure) {
//            Closure closure = (Closure) item;
//            return closure.call((Object[]) args);
//        } else {
//            throw new groovy.lang.MissingMethodException(name, getClass(), (Object[]) args);
//        }
//    }
}

