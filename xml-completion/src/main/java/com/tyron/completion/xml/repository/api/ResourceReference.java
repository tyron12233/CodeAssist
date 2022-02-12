package com.tyron.completion.xml.repository.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.tyron.builder.compiler.manifest.resources.ResourceType;

import java.io.Serializable;
import java.util.Objects;

import javax.annotation.concurrent.Immutable;

/**
 * A resource reference, contains the namespace, type and name. Can be used to look for resources in
 * a resource repository.
 *
 * <p>This is an immutable class.
 */
@Immutable
public final class ResourceReference implements Comparable<ResourceReference>, Serializable {
    @NonNull private final ResourceType resourceType;
    @NonNull private final ResourceNamespace namespace;
    @NonNull private final String name;

    /**
     * Initializes a ResourceReference.
     *
     * @param namespace the namespace of the resource
     * @param resourceType the type of the resource
     * @param name the name of the resource, should not be qualified
     */
    public ResourceReference(
            @NonNull ResourceNamespace namespace,
            @NonNull ResourceType resourceType,
            @NonNull String name) {
        assert resourceType == ResourceType.SAMPLE_DATA || name.indexOf(':') < 0
                : "Qualified name is not allowed: " + name;
        this.namespace = namespace;
        this.resourceType = resourceType;
        this.name = name;
    }

    /** A shorthand for creating a {@link ResourceType#ATTR} resource reference. */
    public static ResourceReference attr(
            @NonNull ResourceNamespace namespace, @NonNull String name) {
        return new ResourceReference(namespace, ResourceType.ATTR, name);
    }

    /** A shorthand for creating a {@link ResourceType#STYLE} resource reference. */
    public static ResourceReference style(
            @NonNull ResourceNamespace namespace, @NonNull String name) {
        return new ResourceReference(namespace, ResourceType.STYLE, name);
    }

    /** A shorthand for creating a {@link ResourceType#STYLEABLE} resource reference. */
    public static ResourceReference styleable(
            @NonNull ResourceNamespace namespace, @NonNull String name) {
        return new ResourceReference(namespace, ResourceType.STYLEABLE, name);
    }

    /** Returns the name of the resource, as defined in the XML. */
    @NonNull
    public String getName() {
        return name;
    }

    /**
     * If the package name of the namespace is not null, returns the name of the resource prefixed
     * by the package name with a colon separator. Otherwise returns the name of the resource.
     */
    public String getQualifiedName() {
        String packageName = namespace.getPackageName();
        return packageName == null ? name : packageName + ':' + name;
    }

    @NonNull
    public ResourceType getResourceType() {
        return resourceType;
    }

    @NonNull
    public ResourceNamespace getNamespace() {
        return namespace;
    }

    /**
     * Returns whether the resource is a framework resource ({@code true}) or a project resource
     * ({@code false}).
     *
     * @deprecated all namespaces should be handled not just "android:".
     */
    @Deprecated
    public final boolean isFramework() {
        return ResourceNamespace.ANDROID.equals(namespace);
    }

    @NonNull
    public ResourceUrl getResourceUrl() {
        return ResourceUrl.create(namespace.getPackageName(), resourceType, name);
    }

    /**
     * Returns a {@link ResourceUrl} that can be used to refer to this resource from the given
     * namespace. This means the namespace part of the {@link ResourceUrl} will be null if the
     * context namespace is the same as the namespace of this resource.
     *
     * <p>This method assumes no namespace prefixes (aliases) are defined, so the returned {@link
     * ResourceUrl} will use the full package name of the target namespace, if necessary. Most use
     * cases should attempt to call the overloaded method instead and provide a {@link
     * ResourceNamespace.Resolver} from the XML element where the {@link ResourceUrl} will be used.
     *
     * @see #getRelativeResourceUrl(ResourceNamespace, ResourceNamespace.Resolver)
     */
    @NonNull
    public ResourceUrl getRelativeResourceUrl(@NonNull ResourceNamespace context) {
        return getRelativeResourceUrl(context, ResourceNamespace.Resolver.EMPTY_RESOLVER);
    }

    /**
     * Returns a {@link ResourceUrl} that can be used to refer to this resource from the given
     * namespace. This means the namespace part of the {@link ResourceUrl} will be null if the
     * context namespace is the same as the namespace of this resource.
     *
     * <p>This method uses the provided {@link ResourceNamespace.Resolver} to find the short prefix
     * that can be used to refer to the target namespace. If it is not found, the full package name
     * is used.
     */
    @NonNull
    public ResourceUrl getRelativeResourceUrl(
            @NonNull ResourceNamespace context, @NonNull ResourceNamespace.Resolver resolver) {
        String namespaceString;
        if (namespace.equals(context)) {
            namespaceString = null;
        } else {
            String prefix = resolver.uriToPrefix(namespace.getXmlNamespaceUri());
            if (prefix != null) {
                namespaceString = prefix;
            } else {
                namespaceString = namespace.getPackageName();
            }
        }

        return ResourceUrl.create(namespaceString, resourceType, name);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ResourceReference reference = (ResourceReference) obj;

        if (resourceType != reference.resourceType) return false;
        if (!namespace.equals(reference.namespace)) return false;
        if (!name.equals(reference.name)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(resourceType, namespace, name);
    }

    @Override
    public int compareTo(@NonNull ResourceReference other) {
        int diff = resourceType.compareTo(other.resourceType);
        if (diff != 0) {
            return diff;
        }
        diff = namespace.compareTo(other.namespace);
        if (diff != 0) {
            return diff;
        }
        return name.compareTo(other.name);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("namespace", namespace)
                .add("type", resourceType)
                .add("name", name)
                .toString();
    }
}

