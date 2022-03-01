package com.tyron.xml.completion.repository;

import android.content.res.Resources;

import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceValue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class AndroidResourceRepository extends SimpleResourceRepository {


    private static AndroidResourceRepository sInstance = null;

    public static AndroidResourceRepository getInstance() {
        if (sInstance == null) {
            File file = XmlRepository.getOrExtractFiles();
            File parent = file.getParentFile();
            assert parent != null;
            File resDir = parent.getParentFile();
            assert resDir != null;
            sInstance = new AndroidResourceRepository(resDir, ResourceNamespace.ANDROID);
        }
        return sInstance;
    }

    public AndroidResourceRepository(File resDir, ResourceNamespace namespace) {
        super(resDir, namespace);
    }

    @NotNull
    @Override
    public ResourceValue getValue(ResourceReference reference) {
        return super.getValue(reference);
    }

    @Override
    public boolean hasResources(@NotNull ResourceNamespace namespace,
                                @NotNull ResourceType resourceType,
                                @NotNull String resourceName) {
        return !mTable.getOrPutEmpty(namespace, resourceType).get(resourceName).isEmpty();
    }

    @NotNull
    @Override
    public List<ResourceItem> getResources(@NotNull ResourceNamespace namespace, @NotNull ResourceType type, @NotNull Predicate<ResourceItem> filter) {
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

    @NotNull
    @Override
    public List<ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                           @NotNull ResourceType resourceType,
                                           @NotNull String resourceName) {
        return mTable.getOrPutEmpty(namespace, resourceType).get(resourceName);
    }

    @NotNull
    @Override
    public ListMultimap<String, ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                                           @NotNull ResourceType resourceType) {
        return mTable.getOrPutEmpty(namespace, resourceType);
    }
}
