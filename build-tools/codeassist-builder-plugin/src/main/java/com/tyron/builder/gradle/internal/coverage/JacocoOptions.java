package com.tyron.builder.gradle.internal.coverage;

import com.android.annotations.NonNull;
import com.tyron.builder.api.dsl.TestCoverage;
import javax.inject.Inject;
import org.jetbrains.annotations.NotNull;

public class JacocoOptions implements com.tyron.builder.api.dsl.JacocoOptions, TestCoverage {

    /** Default JaCoCo version. */
    public static final String DEFAULT_VERSION = "0.8.8";

    @Inject
    public JacocoOptions() {}

    @NonNull private String jacocoVersion = DEFAULT_VERSION;

    @Override
    @NonNull
    public String getVersion() {
        return jacocoVersion;
    }

    @Override
    public void setVersion(@NonNull String version) {
        this.jacocoVersion = version;
    }

    @NotNull
    @Override
    public String getJacocoVersion() {
        return jacocoVersion;
    }

    @Override
    public void setJacocoVersion(@NotNull String jacocoVersion) {
        this.jacocoVersion = jacocoVersion;
    }
}
