package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;
import com.tyron.builder.jvm.toolchain.JavaToolchainSpec;

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
