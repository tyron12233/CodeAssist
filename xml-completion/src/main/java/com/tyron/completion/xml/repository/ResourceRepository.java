package com.tyron.completion.xml.repository;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.model.Library;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ResourceRepository extends SimpleResourceRepository {

    private final AndroidModule mModule;
    private final AndroidResourceRepository mAndroidRepository;

    public ResourceRepository(AndroidModule module) {
        super(module.getAndroidResourcesDirectory(),
              ResourceNamespace.fromPackageName(module.getPackageName()));
        mModule = module;
        mAndroidRepository = AndroidResourceRepository.getInstance();
    }

    @Override
    public void initialize() throws IOException {
        mAndroidRepository.initialize();

        File resDir = mModule.getAndroidResourcesDirectory();
        parse(resDir, getNamespace(), null);

        for (File library : mModule.getLibraries()) {
            File parent = library.getParentFile();
            if (parent == null) {
                continue;
            }

            ResourceNamespace namespace;
            File manifest = new File(parent, "AndroidManifest.xml");
            try {
                ManifestData data = AndroidManifestParser.parse(manifest);
                namespace = ResourceNamespace.fromPackageName(data.getPackage());
            } catch (IOException ignored) {
                namespace = ResourceNamespace.RES_AUTO;
            }

            File libraryResDir = new File(parent, "res");
            if (!libraryResDir.exists()) {
                continue;
            }

            Library lib = mModule.getLibrary(parent.getName());
            String name = null;
            if (lib != null) {
                name = lib.getSourceFile()
                        .getName();
            }

            parse(libraryResDir, namespace, name);
        }
    }

    @NonNull
    @Override
    public ResourceValue getValue(ResourceReference reference) {
        //noinspection deprecation other references are handled below
        if (reference.isFramework()) {
            try {
                return mAndroidRepository.getValue(reference);
            } catch (Resources.NotFoundException ignored) {
                // try again below
            }
        }
        return super.getValue(reference);
    }

    @NonNull
    @Override
    public List<ResourceItem> getResources(@NonNull ResourceNamespace namespace,
                                           @NonNull ResourceType resourceType,
                                           @NonNull String resourceName) {
        if (namespace == ResourceNamespace.ANDROID) {
            try {
                return mAndroidRepository.getResources(namespace, resourceType, resourceName);
            } catch (Resources.NotFoundException ignored) {
                // try again below
            }
        }
        return super.getResources(namespace, resourceType, resourceName);
    }
}
