package com.tyron.completion.xml.repository;

import android.content.res.Resources;
import android.graphics.Color;

import androidx.annotation.NonNull;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.MultimapBuilder;
import com.tyron.builder.compiler.manifest.configuration.Configurable;
import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceFolderType;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.Repository;
import com.tyron.completion.xml.repository.ResourceItem;
import com.tyron.completion.xml.repository.ResourceTable;
import com.tyron.completion.xml.repository.SimpleResourceItem;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
import com.tyron.completion.xml.repository.api.StyleResourceValue;
import com.tyron.completion.xml.repository.parser.ResourceParser;
import com.tyron.completion.xml.repository.parser.ValuesXmlParser;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimpleResourceRepository implements Repository {

    private static final ImmutableMap<ResourceFolderType, ResourceParser> sParsers;
    static {
        ImmutableMap.Builder<ResourceFolderType, ResourceParser> parsers = ImmutableMap.builder();
        parsers.put(ResourceFolderType.VALUES, new ValuesXmlParser());
        sParsers = parsers.build();
    }

    private final File mResDir;
    private final ResourceNamespace mNamespace;
    protected final ResourceTable mTable = new ResourceTable();
    private FolderConfiguration mConfiguration;

    public SimpleResourceRepository(File resDir, ResourceNamespace namespace) {
        mResDir = resDir;
        mNamespace = namespace;

        mConfiguration = FolderConfiguration.createDefault();
    }

    @Override
    public void initialize() throws IOException {
        parse(mResDir, mNamespace);
    }

    protected void parse(File resDir, ResourceNamespace namespace) throws IOException {
        Collection<File> dirs = FileUtils.listFilesAndDirs(resDir, FalseFileFilter.INSTANCE,
                                                           TrueFileFilter.INSTANCE);
        for (File dir : dirs) {
            ResourceFolderType folderType = ResourceFolderType.getFolderType(dir.getName());
            if (folderType == null) {
                continue;
            }
            ResourceParser parser = sParsers.get(folderType);
            if (parser == null) {
                continue;
            }

            Collection<File> xmlFiles =
                    FileUtils.listFiles(dir, new SuffixFileFilter("xml"), FalseFileFilter.INSTANCE);

            for (File xmlFile : xmlFiles) {
                List<ResourceValue> values = parser.parse(xmlFile, namespace);
                for (ResourceValue value : values) {
                    ListMultimap<String, ResourceItem> tableValue =
                            mTable.getOrPutEmpty(value.getNamespace(), value.getResourceType());
                    tableValue.put(value.getName(), new SimpleResourceItem(value, dir.getName()));
                }
            }
        }
    }

    @NonNull
    @Override
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                           @NonNull ResourceType resourceType,
                                           @NonNull String resourceName) {
        ListMultimap<String, ResourceItem> publicResources =
                mTable.get(namespace, ResourceType.PUBLIC);
        if (publicResources != null) {
            if (!publicResources.containsKey(resourceName)) {
                return ImmutableList.of();
            }
        }
        return mTable.getOrPutEmpty(namespace, resourceType).get(resourceName);
    }

    @NonNull
    @Override
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                           @NonNull ResourceType type, @NonNull Predicate<ResourceItem> filter) {
        ListMultimap<String, ResourceItem> value =
                mTable.get(namespace, type);
        List<ResourceItem> items = new ArrayList<>();
        for (Map.Entry<String, ResourceItem> entry : value.entries()) {
            if (filter.test(entry.getValue())) {
                items.add(entry.getValue());
            }
        }
        return items;
    }

    @NonNull
    @Override
    public ListMultimap<String, ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                                           @NonNull ResourceType resourceType) {
        ListMultimap<String, ResourceItem> resources = ArrayListMultimap.create();
        if (namespace.equals(ResourceNamespace.ANDROID)) {
            return AndroidResourceRepository.getInstance().getResources(namespace, resourceType);
        }
        if (namespace.equals(ResourceNamespace.RES_AUTO)) {
            Set<ResourceNamespace> namespaces = mTable.rowKeySet();
            for (ResourceNamespace resourceNamespace : namespaces) {
                ListMultimap<String, ResourceItem> values =
                        mTable.get(resourceNamespace, resourceType);
                if (values != null) {
                    resources.putAll(values);
                }
            }
            return resources;
        }

        ListMultimap<String, ResourceItem> publicResources =
                mTable.getOrPutEmpty(namespace, ResourceType.PUBLIC);
        return mTable.getOrPutEmpty(namespace, resourceType);
    }

    @Override
    public boolean hasResources(@NonNull ResourceNamespace namespace,
                                @NonNull ResourceType resourceType,
                                @NonNull String resourceName) {
        return !mTable.getOrPutEmpty(namespace, resourceType).isEmpty();
    }

    @NonNull
    @Override
    public ResourceNamespace getNamespace() {
        return mNamespace;
    }

    @NonNull
    public ResourceValue getValue(ResourceReference reference) {
        List<ResourceItem> resourceItems = getResources(reference);
        if (resourceItems.isEmpty()) {
            throw new Resources.NotFoundException();
        }
        Map<Configurable, ResourceItem> map = new HashMap<>();
        for (ResourceItem item : resourceItems) {
            FolderConfiguration configuration = item.getConfiguration();
            map.put(() -> configuration, item);
        }

        Configurable matching = mConfiguration.findMatchingConfigurable(map.keySet());
        if (matching == null) {
            throw new Resources.NotFoundException();
        }

        return Objects.requireNonNull(Objects.requireNonNull(map.get(matching))
                                              .getResourceValue());
    }

    @NonNull
    public ResourceValue getValue(ResourceNamespace resourceNamespace, String name, boolean resolveRefs) {
//        if (resourceNamespace.equals(ResourceNamespace.ANDROID)) {
//            return mAndroidRepository.getValue(resourceNamespace, name, resolveRefs);
//        }
//
//        mTable.get()
//
//        Map<String, ResourceValue> valueMap = mNamespaceValuesMap.get(resourceNamespace);
//        if (valueMap == null) {
//            throw new Resources.NotFoundException(
//                    "Unable to find resource " + name + " from namespace " + resourceNamespace);
//        }
//
//        ResourceValue resourceValue = valueMap.get(name);
//        if (resourceValue == null) {
//            throw new Resources.NotFoundException("Unable to find resource " + name);
//        }
//
//        ResourceReference reference = resourceValue.getReference();
//        if (reference != null && resolveRefs) {
//            return getValue(reference);
//        }
//
//        return resourceValue;
        throw new Resources.NotFoundException();
    }

    public int getColor(ResourceNamespace namespace, String name) {
        ResourceValue result = getValue(new ResourceReference(namespace, ResourceType.COLOR, name));
        return Color.parseColor(result.getValue());
    }

    public String getString(String name) {
        return getString(mNamespace, name);
    }

    public String getString(ResourceNamespace namespace, String name) {
        ResourceValue value = getValue(namespace, name, true);
        if (value.getResourceType() != ResourceType.STRING) {
            throw new Resources.NotFoundException(
                    "Found resource is not a string but is a " + value.getResourceType()
                            .getDisplayName());
        }
        return value.getValue();
    }

    public StyleResourceValue getStyle(String name) {
        return getStyle(mNamespace, name);
    }

    public StyleResourceValue getStyle(ResourceNamespace namespace, String name) {
        ResourceValue value = getValue(namespace, name, true);
        if (value.getResourceType() != ResourceType.STYLE) {
            throw new Resources.NotFoundException(
                    "Found resource is not a style but is a " + value.getResourceType()
                            .getDisplayName());
        }
        return (StyleResourceValue) value;
    }
}
