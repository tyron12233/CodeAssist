package com.tyron.xml.completion.repository.api;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import com.google.common.base.MoreObjects;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.util.Objects;

/** Simple implementation of the {@link ResourceValue} interface. */
public class ResourceValueImpl implements ResourceValue {
    @NotNull private final ResourceType resourceType;
    @NotNull private final ResourceNamespace namespace;
    @NotNull private final String name;

    @Nullable private final String libraryName;
    @Nullable private String value;

    @NotNull
    protected transient ResourceNamespace.Resolver mNamespaceResolver =
            ResourceNamespace.Resolver.EMPTY_RESOLVER;

    public ResourceValueImpl(
            @NotNull ResourceNamespace namespace,
            @NotNull ResourceType type,
            @NotNull String name,
            @Nullable String value,
            @Nullable String libraryName) {
        this.namespace = namespace;
        this.resourceType = type;
        this.name = name;
        this.value = value;
        this.libraryName = libraryName;
    }

    public ResourceValueImpl(
            @NotNull ResourceNamespace namespace,
            @NotNull ResourceType type,
            @NotNull String name,
            @Nullable String value) {
        this(namespace, type, name, value, null);
    }

    public ResourceValueImpl(
            @NotNull ResourceReference reference,
            @Nullable String value,
            @Nullable String libraryName) {
        this(reference.getNamespace(), reference.getResourceType(), reference.getName(), value,
             libraryName);
    }

    public ResourceValueImpl(@NotNull ResourceReference reference, @Nullable String value) {
        this(reference, value, null);
    }

    @Override
    @NotNull
    public final ResourceType getResourceType() {
        return resourceType;
    }

    @Override
    @NotNull
    public final ResourceNamespace getNamespace() {
        return namespace;
    }

    @Override
    @NotNull
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
    @NotNull
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
    public void replaceWith(@NotNull ResourceValue value) {
        this.value = value.getValue();
    }

    @Override
    @NotNull
    public ResourceNamespace.Resolver getNamespaceResolver() {
        return mNamespaceResolver;
    }

    /**
     * Specifies logic used to resolve namespace aliases for values that come from XML files.
     *
     * <p>This method is meant to be called by the XML parser that created this {@link
     * ResourceValue}.
     */
    public void setNamespaceResolver(@NotNull ResourceNamespace.Resolver resolver) {
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
    @NotNull
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("namespace", getNamespace())
                .add("type", getResourceType())
                .add("name", getName())
                .add("value", getValue())
                .toString();
    }
}

