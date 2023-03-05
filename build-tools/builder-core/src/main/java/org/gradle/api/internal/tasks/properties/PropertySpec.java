package org.gradle.api.internal.tasks.properties;

public interface PropertySpec extends Comparable<PropertySpec> {
    String getPropertyName();
}
