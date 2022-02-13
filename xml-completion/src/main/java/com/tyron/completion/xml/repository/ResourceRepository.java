package com.tyron.completion.xml.repository;

import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.tyron.builder.project.api.AndroidModule;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;

import java.io.File;
import java.io.IOException;

public class ResourceRepository extends SimpleResourceRepository {

    private final AndroidModule mModule;
    private final AndroidResourceRepository mAndroidRepository;

    public ResourceRepository(AndroidModule module) {
        super(module.getAndroidResourcesDirectory(), ResourceNamespace.fromPackageName(module.getPackageName()));
        mModule = module;
        mAndroidRepository = AndroidResourceRepository.getInstance();
    }

    @Override
    public void initialize() throws IOException {
        mAndroidRepository.initialize();

        File resDir = mModule.getAndroidResourcesDirectory();
        parse(resDir, getNamespace());
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
}
