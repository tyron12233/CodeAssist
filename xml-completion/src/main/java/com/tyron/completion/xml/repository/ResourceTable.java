package com.tyron.completion.xml.repository;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ForwardingTable;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;

import java.util.HashMap;
import java.util.List;

/**
 * Mutable, three-dimensional table for storing {@link ResourceItem}, indexed by components of a
 * {@link ResourceReference}.
 *
 * <p>The first dimension is namespace. Can be taken straight from {@link ResourceReference}.
 *
 * <p>The second dimension is the type of resources in question.
 *
 * <p>The value is a multimap that maps resource name (third dimension) to all matching {@link
 * ResourceItem}s. There can be multiple items defined under the same name with different resource
 * qualifiers.
 *
 */
public final class ResourceTable
        extends ForwardingTable<ResourceNamespace, ResourceType, ListMultimap<String, ResourceItem>> {

    private final Table<ResourceNamespace, ResourceType, ListMultimap<String, ResourceItem>>
            delegate =
            Tables.newCustomTable(
                    new HashMap<>(), () -> Maps.newEnumMap(ResourceType.class));

    @Override
    protected Table<ResourceNamespace, ResourceType, ListMultimap<String, ResourceItem>>
    delegate() {
        return delegate;
    }

    /**
     * Removes the given {@link ResourceItem} from the table, making sure no empty multimaps are
     * left as {@link Table} values. This way the set of rows and columns we get from the {@link
     * Table} reflects reality.
     */
    public void remove(ResourceItem resourceItem) {
        ResourceNamespace namespace = resourceItem.getNamespace();
        ResourceType type = resourceItem.getType();
        String name = resourceItem.getName();

        ListMultimap<String, ResourceItem> multimap = get(namespace, type);
        if (multimap != null) {
            multimap.remove(name, resourceItem);
            if (multimap.isEmpty()) {
                remove(namespace, type);
            }
        }
    }

    /**
     * Gets the corresponding multimap from the table, if necessary creating an empty one and
     * putting it in the table.
     */
    @NonNull
    public ListMultimap<String, ResourceItem> getOrPutEmpty(
            @NonNull ResourceNamespace namespace, @NonNull ResourceType resourceType) {
        ListMultimap<String, ResourceItem> multimap = get(namespace, resourceType);
        if (multimap == null) {
            multimap = ArrayListMultimap.create();
            put(namespace, resourceType, multimap);
        }
        return multimap;
    }

    @Nullable
    public List<ResourceItem> get(@NonNull ResourceReference reference) {
        ListMultimap<String, ResourceItem> multimap =
                get(reference.getNamespace(), reference.getResourceType());
        if (multimap == null) {
            return null;
        }

        return multimap.get(reference.getName());
    }
}
