package com.tyron.xml.completion.repository;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ListMultimap;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceValue;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public interface Repository {

    /**
     * Returns the resources with the given namespace, type and name.
     *
     * @param namespace    the namespace of the resources to return
     * @param resourceType the type of the resources to return
     * @param resourceName the bane of the resources to return
     * @return the resources matching the namespace, type, and satisfying the name filter
     */
    @NotNull
    List<ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                    @NotNull ResourceType resourceType,
                                    @NotNull String resourceName);

    @NotNull
    default List<ResourceItem> getResources(@NotNull ResourceReference reference) {
        return getResources(reference.getNamespace(), reference.getResourceType(),
                            reference.getName());
    }

    @NotNull
    List<ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                            @NotNull ResourceType type,
                                            @NotNull Predicate<ResourceItem> filter);

    /**
     * Returns the resources with the given namespace and type keyed by resource names.
     * If you need only the names of the resources, but not the resources themselves, call
     * {@link #getResourceNames(ResourceNamespace, ResourceType)} instead.
     *
     * @param namespace    the namespace of the resources to return
     * @param resourceType the type of the resources to return
     * @return the resources matching the namespace and type
     */
    @NotNull
    ListMultimap<String, ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                                    @NotNull ResourceType resourceType);

    boolean hasResources(@NotNull ResourceNamespace namespace,
                         @NotNull ResourceType resourceType,
                         @NotNull String resourceName);

    /**
     * Returns the names of resources with the given namespace and type. For some resource
     * repositories calling this method can be more efficient than calling
     * {@link #getResources(ResourceNamespace, ResourceType)} and then
     * {@link ListMultimap#keySet()}.
     *
     * @param namespace    the namespace of the resources to return
     * @param resourceType the type of the resources to return
     * @return the names of the resources matching the namespace and type
     */
    @NotNull
    default Set<String> getResourceNames(@NotNull ResourceNamespace namespace,
                                         @NotNull ResourceType resourceType) {
        return getResources(namespace, resourceType).keySet();
    }

    @NotNull
    ResourceNamespace getNamespace();

    /**
     * Return all the namespaces stored in this repository
     */
    @NotNull
    List<ResourceNamespace> getNamespaces();

    /**
     * Return all the resource types stored in this repository
     */
    @NotNull
    List<ResourceType> getResourceTypes();

    ResourceValue getValue(ResourceReference reference);

    default ResourceValue getValue(String name, boolean resolveRefs) {
        return getValue(getNamespace(), name, resolveRefs);
    }

    ResourceValue getValue(ResourceNamespace namespace, String name, boolean resolveRefs);

    void initialize() throws IOException;

    void updateFile(@NotNull File file, @Nullable String contents) throws IOException;
}
