package com.tyron.completion.xml.repository;

import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.tyron.builder.compiler.manifest.resources.ResourceFolderType;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
import com.tyron.completion.xml.repository.api.ResourceValueImpl;
import com.tyron.completion.xml.repository.api.StyleResourceValue;
import com.tyron.completion.xml.repository.api.StyleableResourceValue;
import com.tyron.completion.xml.repository.parser.ResourceParser;
import com.tyron.completion.xml.repository.parser.ValuesXmlParser;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.SuffixFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourceRepository implements Repository {

    private static final ImmutableMap<ResourceFolderType, ResourceParser> sParsers;

    static {
        ImmutableMap.Builder<ResourceFolderType, ResourceParser> parsers = ImmutableMap.builder();
        parsers.put(ResourceFolderType.VALUES, new ValuesXmlParser());
        sParsers = parsers.build();
    }

    private final Map<ResourceNamespace, Map<String, ResourceValue>> mNamespaceValuesMap =
            new HashMap<>();

    private final AndroidModule mModule;
    private final ResourceNamespace mPackageNamespace;
    private final AndroidResourceRepository mAndroidRepository;

    public ResourceRepository(AndroidModule module) {
        mModule = module;
        mPackageNamespace = ResourceNamespace.fromPackageName(module.getPackageName());
        mAndroidRepository = AndroidResourceRepository.getInstance();
    }

    @Override
    public void initialize() throws IOException {
        mAndroidRepository.initialize();

        File resDir = mModule.getAndroidResourcesDirectory();
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
                List<ResourceValue> values = parser.parse(xmlFile, mPackageNamespace);
                for (ResourceValue value : values) {
                    mNamespaceValuesMap.compute(mPackageNamespace, (k, v) -> {
                        if (v == null) {
                            v = new HashMap<>();
                        }
                        v.put(value.getName(), value);
                        return v;
                    });
                }
            }
        }
    }

    @NonNull
    @Override
    public ResourceNamespace getNamespace() {
        return mPackageNamespace;
    }

    @NonNull
    public ResourceValue getValue(ResourceReference reference) {
        return getValue(reference.getNamespace(), reference.getName(), true);
    }

    @NonNull
    public ResourceValue getValue(ResourceNamespace resourceNamespace, String name, boolean resolveRefs) {
        if (resourceNamespace.equals(ResourceNamespace.ANDROID)) {
            return mAndroidRepository.getValue(resourceNamespace, name, resolveRefs);
        }

        Map<String, ResourceValue> valueMap = mNamespaceValuesMap.get(resourceNamespace);
        if (valueMap == null) {
            throw new Resources.NotFoundException(
                    "Unable to find resource " + name + " from namespace " + resourceNamespace);
        }

        ResourceValue resourceValue = valueMap.get(name);
        if (resourceValue == null) {
            throw new Resources.NotFoundException("Unable to find resource " + name);
        }

        ResourceReference reference = resourceValue.getReference();
        if (reference != null && resolveRefs) {
            return getValue(reference);
        }

        return resourceValue;
    }

    public int getColor(String name) {
        return getColor(mPackageNamespace, name);
    }

    public int getColor(ResourceNamespace namespace, String name) {
        ResourceValue value = getValue(namespace, name, true);
        if (value.getResourceType() != ResourceType.COLOR) {
            throw new Resources.NotFoundException(
                    "Found resource is not a color but is a " + value.getResourceType()
                            .getDisplayName());
        }
        return Color.parseColor(value.getValue());
    }

    public String getString(String name) {
        return getString(mPackageNamespace, name);
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
        return getStyle(mPackageNamespace, name);
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
