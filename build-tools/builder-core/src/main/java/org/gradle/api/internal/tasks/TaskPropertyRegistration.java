package org.gradle.api.internal.tasks;


public interface TaskPropertyRegistration {
    String getPropertyName();
    StaticValue getValue();
    boolean isOptional();
}