package org.gradle.jvm.toolchain.internal;

import org.gradle.jvm.toolchain.JavaToolchainSpec;

public interface ToolchainSpecInternal extends JavaToolchainSpec {

    boolean isConfigured();

}
