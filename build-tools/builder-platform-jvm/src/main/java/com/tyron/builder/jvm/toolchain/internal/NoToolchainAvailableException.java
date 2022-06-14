package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;

public class NoToolchainAvailableException extends BuildException {

    public NoToolchainAvailableException(JavaToolchainSpec filter, boolean autoDetect, boolean autoDownload) {
        super("No compatible toolchains found for request filter: " + filter.getDisplayName() + " (auto-detect " + autoDetect + ", auto-download " + autoDownload + ")");
    }
}
