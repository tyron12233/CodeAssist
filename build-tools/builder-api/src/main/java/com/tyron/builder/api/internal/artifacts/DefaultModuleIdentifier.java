package com.tyron.builder.api.internal.artifacts;
import com.google.common.base.Objects;
import com.tyron.builder.api.artifacts.ModuleIdentifier;

import javax.annotation.Nullable;

public class DefaultModuleIdentifier implements ModuleIdentifier {
    private final String group;
    private final String name;
    private final int hashCode;

    private DefaultModuleIdentifier(@Nullable String group, String name) {
        this.group = group;
        this.name = name;
        this.hashCode = Objects.hashCode(group, name);
    }

    public static ModuleIdentifier newId(ModuleIdentifier other) {
        if (other instanceof DefaultModuleIdentifier) {
            return other;
        }
        return newId(other.getGroup(), other.getName());
    }

    public static ModuleIdentifier newId(@Nullable String group, String name) {
        return new DefaultModuleIdentifier(group, name);
    }

    @Override
    public String getGroup() {
        return group;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return group + ":" + name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultModuleIdentifier that = (DefaultModuleIdentifier) o;
        return hashCode == that.hashCode &&
               Objects.equal(group, that.group) &&
               Objects.equal(name, that.name);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}