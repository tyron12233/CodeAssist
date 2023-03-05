package org.gradle.jvm.toolchain.internal;

import org.gradle.api.tasks.Input;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainSpec;

public class JavaToolchainInput {

    private final JavaLanguageVersion javaLanguageVersion;
    private final String vendor;
    private final String implementation;

    public JavaToolchainInput(JavaToolchainSpec spec) {
        this.javaLanguageVersion = spec.getLanguageVersion().getOrNull();
        this.vendor = spec.getVendor().get().toString();
        this.implementation = spec.getImplementation().get().toString();
    }

    @Input
    JavaLanguageVersion getLanguageVersion() {
        return javaLanguageVersion;
    }

    @Input
    String getVendor() {
        return vendor;
    }

    @Input
    String getImplementation() {
        return implementation;
    }

}
