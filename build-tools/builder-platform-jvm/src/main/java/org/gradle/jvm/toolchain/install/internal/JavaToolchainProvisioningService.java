package org.gradle.jvm.toolchain.install.internal;

import org.gradle.jvm.toolchain.JavaToolchainSpec;

import java.io.File;
import java.util.Optional;

public interface JavaToolchainProvisioningService {

    Optional<File> tryInstall(JavaToolchainSpec spec);

}
