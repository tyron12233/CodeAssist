package com.tyron.completion.xml.repository;

import com.tyron.completion.xml.XmlRepository;
import com.tyron.completion.xml.repository.api.ResourceNamespace;

import java.io.File;

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
}
