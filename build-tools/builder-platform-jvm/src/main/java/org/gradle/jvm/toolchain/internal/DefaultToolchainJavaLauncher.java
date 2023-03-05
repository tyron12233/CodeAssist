package org.gradle.jvm.toolchain.internal;

import org.gradle.api.file.RegularFile;
import org.gradle.api.tasks.Internal;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaInstallationMetadata;

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
