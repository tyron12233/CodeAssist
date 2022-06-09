package com.tyron.builder.internal.jvm.inspection;

import com.tyron.builder.internal.jvm.Jvm;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CachingJvmMetadataDetector implements JvmMetadataDetector {

    private final Map<File, JvmInstallationMetadata> javaMetadata = new ConcurrentHashMap<>();
    private final JvmMetadataDetector delegate;

    public CachingJvmMetadataDetector(JvmMetadataDetector delegate) {
        this.delegate = delegate;
        getMetadata(Jvm.current().getJavaHome());
    }

    @Override
    public JvmInstallationMetadata getMetadata(File javaHome) {
        javaHome = resolveSymlink(javaHome);
        return javaMetadata.computeIfAbsent(javaHome, delegate::getMetadata);
    }

    private File resolveSymlink(File jdkPath) {
        try {
            return jdkPath.getCanonicalFile();
        } catch (IOException e) {
            return jdkPath;
        }
    }

}
