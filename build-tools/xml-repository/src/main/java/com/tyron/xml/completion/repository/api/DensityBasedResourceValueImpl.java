package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.tyron.builder.compiler.manifest.resources.Density;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.Objects;

public class DensityBasedResourceValueImpl extends ResourceValueImpl
        implements DensityBasedResourceValue {
    @NotNull private final Density density;

    public DensityBasedResourceValueImpl(
            @NotNull ResourceNamespace namespace,
            @NotNull ResourceType type,
            @NotNull String name,
            @Nullable String value,
            @NotNull Density density,
            @Nullable String libraryName) {
        super(namespace, type, name, value, libraryName);
        this.density = density;
    }

    public DensityBasedResourceValueImpl(
            @NotNull ResourceReference reference,
            @Nullable String value,
            @NotNull Density density) {
        super(reference, value);
        this.density = density;
    }

    @Override
    @NotNull
    public final Density getResourceDensity() {
        return density;
    }

    @Override
    @NotNull
    public String toString() {
        return "DensityBasedResourceValue ["
                + getResourceType() + "/" + getName() + " = " + getValue()
                + " (density:" + density + ", framework:" + isFramework() + ")]";
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), density);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!super.equals(obj)) return false;
        if (getClass() != obj.getClass()) return false;
        DensityBasedResourceValueImpl other = (DensityBasedResourceValueImpl) obj;
        return Objects.equals(density, other.density);
    }
}
