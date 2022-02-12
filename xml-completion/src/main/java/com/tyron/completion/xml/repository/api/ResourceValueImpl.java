package com.tyron.completion.xml.repository.api;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;

import com.google.common.base.MoreObjects;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.Objects;

/** Simple implementation of the {@link ResourceValue} interface. */
public class ResourceValueImpl implements ResourceValue {
    @NonNull private final ResourceType resourceType;
    @NonNull private final ResourceNamespace namespace;
    @NonNull private final String name;

    @Nullable private final String libraryName;
    @Nullable private String value;

    @NonNull
    protected transient ResourceNamespace.Resolver mNamespaceResolver =
            ResourceNamespace.Resolver.EMPTY_RESOLVER;

    public ResourceValueImpl(
            @NonNull ResourceNamespace namespace,
            @NonNull ResourceType type,
            @NonNull String name,
            @Nullable String value,
            @Nullable String libraryName) {
        this.namespace = namespace;
        this.resourceType = type;
        this.name = name;
        this.value = value;
        this.libraryName = libraryName;
    }

    public ResourceValueImpl(
            @NonNull ResourceNamespace namespace,
            @NonNull ResourceType type,
            @NonNull String name,
            @Nullable String value) {
        this(namespace, type, name, value, null);
    }

    public ResourceValueImpl(
            @NonNull ResourceReference reference,
            @Nullable String value,
            @Nullable String libraryName) {
        this(reference.getNamespace(), reference.getResourceType(), reference.getName(), value,
             libraryName);
    }

    public ResourceValueImpl(@NonNull ResourceReference reference, @Nullable String value) {
        this(reference, value, null);
    }

    @Override
    @NonNull
    public final ResourceType getResourceType() {
        return resourceType;
    }

    @Override
    @NonNull
    public final ResourceNamespace getNamespace() {
        return namespace;
    }

    @Override
    @NonNull
    public String getName() {
        return name;
    }

    @Override
    @Nullable
    public final String getLibraryName() {
        return libraryName;
    }

    @Override
    public boolean isUserDefined() {
        // TODO: namespaces
        return !isFramework() && libraryName == null;
    }

    @Override
    public boolean isFramework() {
        // When transferring this across the wire, the instance check won't be correct.
        return ResourceNamespace.ANDROID.equals(namespace);
    }

    @Override
    @Nullable
    public String getValue() {
        return value;
    }

    @Override
    @NonNull
    public ResourceReference asReference() {
        return new ResourceReference(namespace, resourceType, name);
    }

    /**
     * Sets the value of the resource.
     *
     * @param value the new value
     */
    @Override
    public void setValue(@Nullable String value) {
        this.value = value;
    }

    /**
     * Sets the value from another resource.
     *
     * @param value the resource value
     */
    public void replaceWith(@NonNull ResourceValue value) {
        this.value = value.getValue();
    }

    @Override
    @NonNull
    public ResourceNamespace.Resolver getNamespaceResolver() {
        return mNamespaceResolver;
    }

    /**
     * Specifies logic used to resolve namespace aliases for values that come from XML files.
     *
     * <p>This method is meant to be called by the XML parser that created this {@link
     * ResourceValue}.
     */
    public void setNamespaceResolver(@NonNull ResourceNamespace.Resolver resolver) {
        this.mNamespaceResolver = resolver;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ResourceValueImpl that = (ResourceValueImpl) o;
        return resourceType == that.getResourceType()
                && Objects.equals(namespace, that.namespace)
                && Objects.equals(name, that.name)
                && Objects.equals(libraryName, that.libraryName)
                && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceType, namespace, name, libraryName, value);
    }

    @Override
    @NonNull
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("namespace", getNamespace())
                .add("type", getResourceType())
                .add("name", getName())
                .add("value", getValue())
                .toString();
    }
}

