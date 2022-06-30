package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.jvm.toolchain.JavaLauncher;
import com.tyron.builder.jvm.toolchain.JavaInstallationMetadata;

public class DefaultToolchainJavaLauncher implements JavaLauncher {

    private final JavaToolchain javaToolchain;

    public DefaultToolchainJavaLauncher(JavaToolchain javaToolchain) {
        this.javaToolchain = javaToolchain;
    }

    @Override
    @Internal
    public RegularFile getExecutablePath() {
        return javaToolchain.findExecutable("java");
    }

    @Override
    public JavaInstallationMetadata getMetadata() {
        return javaToolchain;
    }
}
