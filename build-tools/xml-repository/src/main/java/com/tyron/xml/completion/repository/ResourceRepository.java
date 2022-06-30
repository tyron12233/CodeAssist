package com.tyron.xml.completion.repository;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.model.Library;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceValue;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ResourceRepository extends SimpleResourceRepository {

    private static boolean sInitializeAndroidRepo = true;

    private final AndroidModule mModule;
    private final AndroidResourceRepository mAndroidRepository;

    public ResourceRepository(AndroidModule module) {
        super(module.getAndroidResourcesDirectory(),
              ResourceNamespace.fromPackageName(module.getPackageName()));
        mModule = module;
        mAndroidRepository = AndroidResourceRepository.getInstance();
    }

    @VisibleForTesting
    public static void setInitializeAndroidRepo(boolean value) {
        sInitializeAndroidRepo = value;
    }

    @Override
    public void initialize() throws IOException {
        if (sInitializeAndroidRepo) {
            mAndroidRepository.initialize();
        }

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

    @NotNull
    @Override
    public ResourceValue getValue(ResourceReference reference) {
        //noinspection deprecation other references are handled below
        if (reference.isFramework()) {
            try {
                return mAndroidRepository.getValue(reference);
            } catch (NotFoundException ignored) {
                // try again below
            }
        }
        return super.getValue(reference);
    }

    @NotNull
    @Override
    public List<ResourceItem> getResources(@NotNull ResourceNamespace namespace,
                                           @NotNull ResourceType resourceType,
                                           @NotNull String resourceName) {
        if (namespace == ResourceNamespace.ANDROID) {
            try {
                return mAndroidRepository.getResources(namespace, resourceType, resourceName);
            } catch (NotFoundException ignored) {
                // try again below
            }
        }
        return super.getResources(namespace, resourceType, resourceName);
    }
}
