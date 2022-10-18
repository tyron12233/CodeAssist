package org.gradle.api.internal.tasks.properties;

public interface BeanPropertyContext {
    void addNested(String propertyName, Object bean);
}