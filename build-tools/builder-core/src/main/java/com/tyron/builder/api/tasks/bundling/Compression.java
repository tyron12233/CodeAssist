package com.tyron.builder.api.tasks.bundling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Specifies the compression which should be applied to a TAR archive.
 */
public enum Compression {
    NONE("tar"),
    GZIP("tgz", "gz"),
    BZIP2("tbz2", "bz2");

    private final String defaultExtension;
    private final List<String> supportedExtensions = new ArrayList<String>(2);

    private Compression(String defaultExtension, String... additionalSupportedExtensions) {
        this.defaultExtension = defaultExtension;
        this.supportedExtensions.addAll(Arrays.asList(additionalSupportedExtensions));
        this.supportedExtensions.add(defaultExtension);
    }

    public String getDefaultExtension(){
        return defaultExtension;
    }

    public List<String> getSupportedExtensions(){
        return supportedExtensions;
    }
}
