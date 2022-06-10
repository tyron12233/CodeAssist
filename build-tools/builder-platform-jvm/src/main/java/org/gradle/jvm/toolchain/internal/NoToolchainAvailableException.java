package org.gradle.jvm.toolchain.internal;

import org.gradle.api.BuildException;
import org.gradle.jvm.toolchain.JavaToolchainSpec;

public class NoToolchainAvailableException extends BuildException {

    public NoToolchainAvailableException(JavaToolchainSpec filter, boolean autoDetect, boolean autoDownload) {
        super("No compatible toolchains found for request filter: " + filter.getDisplayName() + " (auto-detect " + autoDetect + ", auto-download " + autoDownload + ")");
    }
}
