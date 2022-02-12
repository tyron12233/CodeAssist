package com.tyron.completion.xml.repository;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableMap;
import com.tyron.builder.compiler.manifest.resources.ResourceFolderType;
import com.tyron.common.ApplicationProvider;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
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

public class AndroidResourceRepository implements Repository {

    private static AndroidResourceRepository sInstance = null;

    public static AndroidResourceRepository getInstance() {
        if (sInstance == null) {
            File file = XmlRepository.getOrExtractFiles();
            File parent = file.getParentFile();
            assert parent != null;
            File resDir = parent.getParentFile();
            assert resDir != null;
            sInstance = new AndroidResourceRepository(resDir);
        }
        return sInstance;
    }

    private static final ImmutableMap<ResourceFolderType, ResourceParser> sParsers;

    static {
        ImmutableMap.Builder<ResourceFolderType, ResourceParser> parsers = ImmutableMap.builder();
        parsers.put(ResourceFolderType.VALUES, new ValuesXmlParser());
        sParsers = parsers.build();
    }

    private final File mResDir;
    private final Map<ResourceNamespace, Map<String, ResourceValue>> mNamespaceValuesMap =
            new HashMap<>();

    private boolean mInitialized;

    public AndroidResourceRepository(File resDir) {
        mResDir = resDir;
    }

    @Override
    public void initialize() throws IOException {
        if (mInitialized) {
            return;
        }
        Collection<File> dirs = FileUtils.listFilesAndDirs(mResDir, FalseFileFilter.INSTANCE,
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
                List<ResourceValue> values = parser.parse(xmlFile, ResourceNamespace.ANDROID);
                for (ResourceValue value : values) {
                    mNamespaceValuesMap.compute(ResourceNamespace.ANDROID, (k, v) -> {
                        if (v == null) {
                            v = new HashMap<>();
                        }
                        v.put(value.getName(), value);
                        return v;
                    });
                }
            }
        }

        mInitialized = true;
    }

    @NonNull
    @Override
    public ResourceNamespace getNamespace() {
        return ResourceNamespace.ANDROID;
    }

    @Override
    public ResourceValue getValue(ResourceReference reference) {
        return getValue(reference.getNamespace(), reference.getName(), true);
    }

    @Override
    public ResourceValue getValue(ResourceNamespace resourceNamespace, String name, boolean resolveRefs) {
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
}
