package com.tyron.builder.internal.jvm.inspection;

import java.io.File;

public interface JvmMetadataDetector {

    JvmInstallationMetadata getMetadata(File javaHome);

}
