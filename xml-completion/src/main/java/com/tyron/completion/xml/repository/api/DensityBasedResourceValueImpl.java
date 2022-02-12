package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.resources.Density;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.Objects;

public class DensityBasedResourceValueImpl extends ResourceValueImpl
        implements DensityBasedResourceValue {
    @NonNull private final Density density;

    public DensityBasedResourceValueImpl(
            @NonNull ResourceNamespace namespace,
            @NonNull ResourceType type,
            @NonNull String name,
            @Nullable String value,
            @NonNull Density density,
            @Nullable String libraryName) {
        super(namespace, type, name, value, libraryName);
        this.density = density;
    }

    public DensityBasedResourceValueImpl(
            @NonNull ResourceReference reference,
            @Nullable String value,
            @NonNull Density density) {
        super(reference, value);
        this.density = density;
    }

    @Override
    @NonNull
    public final Density getResourceDensity() {
        return density;
    }

    @Override
    @NonNull
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
