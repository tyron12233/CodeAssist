package com.tyron.builder.api.internal.tasks.properties;

public interface BeanPropertyContext {
    void addNested(String propertyName, Object bean);
}