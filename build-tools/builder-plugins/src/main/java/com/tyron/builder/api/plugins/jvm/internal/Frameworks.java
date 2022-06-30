package com.tyron.builder.api.plugins.jvm.internal;

import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

public enum Frameworks {
    JUNIT4("junit:junit", "4.13"),
    JUNIT_JUPITER("org.junit.jupiter:junit-jupiter", "5.7.2"),
    SPOCK("org.spockframework:spock-core", "2.0-groovy-3.0"),
    KOTLIN_TEST("org.jetbrains.kotlin:kotlin-test-junit", "1.5.31"),
    TESTNG("org.testng:testng", "7.4.0"),
    NONE(null, null);

    @Nullable
    private final String module;
    @Nullable
    private final String defaultVersion;

    Frameworks(@Nullable String module, @Nullable String defaultVersion) {
        Preconditions.checkArgument(module != null && defaultVersion != null || module == null && defaultVersion == null, "Either module and version must both be null, or neither be null.");
        this.module = module;
        this.defaultVersion = defaultVersion;
    }

    @Nullable
    public String getDefaultVersion() {
        return defaultVersion;
    }

    @Nullable
    public String getDependency() {
        return getDependency(getDefaultVersion());
    }

    @Nullable
    public String getDependency(String version) {
        if (null != module) {
            return module + ":" + version;
        } else {
            return null;
        }
    }
}
