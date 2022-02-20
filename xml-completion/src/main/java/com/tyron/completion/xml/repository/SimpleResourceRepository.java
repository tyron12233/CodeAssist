package com.tyron.completion.xml.repository;

import android.content.res.Resources;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.tools.r8.Resource;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.tyron.builder.compiler.manifest.configuration.Configurable;
import com.tyron.builder.compiler.manifest.configuration.FolderConfiguration;
import com.tyron.builder.compiler.manifest.resources.ResourceFolderType;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.common.logging.IdeLog;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
import com.tyron.completion.xml.repository.api.StyleResourceValue;
import com.tyron.completion.xml.repository.parser.DrawableXmlParser;
import com.tyron.completion.xml.repository.parser.LayoutXmlParser;
import com.tyron.completion.xml.repository.parser.ResourceParser;
import com.tyron.completion.xml.repository.parser.TemporaryParser;
import com.tyron.completion.xml.repository.parser.ValuesXmlParser;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

public class SimpleResourceRepository implements Repository {

    private static final ImmutableMap<ResourceFolderType, ResourceParser> sParsers;

    static {
        ImmutableMap.Builder<ResourceFolderType, ResourceParser> parsers = ImmutableMap.builder();
        parsers.put(ResourceFolderType.VALUES, new ValuesXmlParser());
        parsers.put(ResourceFolderType.LAYOUT, new LayoutXmlParser());
        parsers.put(ResourceFolderType.MIPMAP, new TemporaryParser(ResourceType.MIPMAP));
        parsers.put(ResourceFolderType.MENU, new TemporaryParser(ResourceType.MENU));
        parsers.put(ResourceFolderType.XML, new TemporaryParser(ResourceType.XML));
        parsers.put(ResourceFolderType.ANIMATOR, new TemporaryParser(ResourceType.ANIMATOR));
        parsers.put(ResourceFolderType.INTERPOLATOR, new TemporaryParser(ResourceType.INTERPOLATOR));
        parsers.put(ResourceFolderType.FONT, new TemporaryParser(ResourceType.FONT));
        parsers.put(ResourceFolderType.DRAWABLE, new TemporaryParser(ResourceType.DRAWABLE));
        parsers.put(ResourceFolderType.ANIM, new TemporaryParser(ResourceType.ANIM));
        sParsers = parsers.build();
    }

    private final Logger logger = IdeLog.getCurrentLogger(this);

    private final File mResDir;
    private final ResourceNamespace mNamespace;
    protected final ResourceTable mTable = new ResourceTable();
    protected final Multimap<File, ResourceItem> mFileItems = ArrayListMultimap.create();

    private FolderConfiguration mConfiguration;

    public SimpleResourceRepository(File resDir, ResourceNamespace namespace) {
        mResDir = resDir;
        mNamespace = namespace;

        mConfiguration = FolderConfiguration.createDefault();
    }

    @Override
    public void initialize() throws IOException {
        parse(mResDir, mNamespace, null);
    }

    protected void parse(File resDir, ResourceNamespace namespace, String name) throws IOException {
        Collection<File> dirs = FileUtils.listFilesAndDirs(resDir, FalseFileFilter.INSTANCE,
                                                           TrueFileFilter.INSTANCE);
        for (File dir : dirs) {

            ResourceParser parser = getParser(dir);
            if (parser == null) {
                continue;
            }

            Collection<File> xmlFiles =
                    FileUtils.listFiles(dir, new SuffixFileFilter("xml"), FalseFileFilter.INSTANCE);
            for (File xmlFile : xmlFiles) {
                try {
                    String contents = FileUtils.readFileToString(xmlFile, StandardCharsets.UTF_8);
                    parseFile(parser, xmlFile, contents, dir.getName(), namespace, name);
                } catch (IOException e) {
                    logger.warning("Unable to parse " + xmlFile.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    @Nullable
    private ResourceParser getParser(@NonNull File directory) {
        ResourceFolderType folderType = ResourceFolderType.getFolderType(directory.getName());
        if (folderType == null) {
            return null;
        }
        return sParsers.get(folderType);
    }

    private void parseFile(@NonNull ResourceParser parser,
                           @NonNull File xmlFile,
                           @NonNull String contents,
                           @NonNull String folderName,
                           @NonNull ResourceNamespace namespace,
                           @Nullable String libraryName) throws IOException {
        List<ResourceValue> values = parser.parse(xmlFile, contents, namespace, libraryName);
        for (ResourceValue value : values) {
            ListMultimap<String, ResourceItem> tableValue =
                    mTable.getOrPutEmpty(value.getNamespace(), value.getResourceType());
            SimpleResourceItem resourceItem = new SimpleResourceItem(value, folderName);
            tableValue.put(value.getName(), resourceItem);

            mFileItems.put(xmlFile, resourceItem);
        }
    }

    @Override
    public void updateFile(@NonNull File file, @NonNull String contents) throws IOException {
        Collection<ResourceItem> existingItems = mFileItems.get(file);
        if (existingItems != null) {
            existingItems.stream()
                    .filter(Objects::nonNull)
                    .forEach(mTable::remove);
        }

        File parent = file.getParentFile();
        if (parent == null) {
            return;
        }

        ResourceParser parser = getParser(parent);
        if (parser == null) {
            // should not happen, but return just in case
            return;
        }

        parseFile(parser, file, contents, parent.getName(), mNamespace, null);
    }

    @NonNull
    @Override
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                           @NonNull ResourceType resourceType,
                                           @NonNull String resourceName) {
        ListMultimap<String, ResourceItem> publicResources =
                mTable.get(namespace, ResourceType.PUBLIC);
        if (publicResources != null && !publicResources.isEmpty()) {
            if (!publicResources.containsKey(resourceName)) {
                return ImmutableList.of();
            }
        }
        return mTable.getOrPutEmpty(namespace, resourceType)
                .get(resourceName);
    }

    @NonNull
    @Override
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                           @NonNull ResourceType type,
                                           @NonNull Predicate<ResourceItem> filter) {
        ListMultimap<String, ResourceItem> value = mTable.get(namespace, type);
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
            return AndroidResourceRepository.getInstance()
                    .getResources(namespace, resourceType);
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
        return !mTable.getOrPutEmpty(namespace, resourceType)
                .isEmpty();
    }

    @NonNull
    @Override
    public ResourceNamespace getNamespace() {
        return mNamespace;
    }

    @NonNull
    @Override
    public List<ResourceNamespace> getNamespaces() {
        return ImmutableList.copyOf(mTable.rowKeySet());
    }

    @NonNull
    @Override
    public List<ResourceType> getResourceTypes() {
        return ImmutableList.copyOf(mTable.columnKeySet());
    }

    @NonNull
    public ResourceValue getValue(ResourceReference reference) {
        if (reference.getName()
                .startsWith("CoordinatorLayout")) {
            if ("androidx.coordinatorlayout".equals(reference.getNamespace()
                                                            .getPackageName())) {
                System.out.println(reference);
            }
        }
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
    public ResourceValue getValue(ResourceNamespace resourceNamespace,
                                  String name,
                                  boolean resolveRefs) {
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
            throw new Resources.NotFoundException("Found resource is not a string but is a " +
                                                  value.getResourceType()
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
            throw new Resources.NotFoundException("Found resource is not a style but is a " +
                                                  value.getResourceType()
                                                          .getDisplayName());
        }
        return (StyleResourceValue) value;
    }
}
