package com.tyron.completion.xml.repository;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;

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

    @NonNull
    @Override
    public ResourceValue getValue(ResourceReference reference) {
        return super.getValue(reference);
    }

    @Override
    public boolean hasResources(@NonNull ResourceNamespace namespace,
                                @NonNull ResourceType resourceType,
                                @NonNull String resourceName) {
        return !mTable.getOrPutEmpty(namespace, resourceType).get(resourceName).isEmpty();
    }

    @NonNull
    @Override
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace, @NonNull ResourceType type, @NonNull Predicate<ResourceItem> filter) {
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
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                           @NonNull ResourceType resourceType,
                                           @NonNull String resourceName) {
        return mTable.getOrPutEmpty(namespace, resourceType).get(resourceName);
    }

    @NonNull
    @Override
    public ListMultimap<String, ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                                           @NonNull ResourceType resourceType) {
        return mTable.getOrPutEmpty(namespace, resourceType);
    }
}
