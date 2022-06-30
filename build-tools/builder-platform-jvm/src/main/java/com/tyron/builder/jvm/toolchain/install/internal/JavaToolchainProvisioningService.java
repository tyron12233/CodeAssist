package com.tyron.builder.jvm.toolchain.install.internal;

import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;

import java.io.File;
import java.util.Optional;

public interface JavaToolchainProvisioningService {

    Optional<File> tryInstall(JavaToolchainSpec spec);

}
