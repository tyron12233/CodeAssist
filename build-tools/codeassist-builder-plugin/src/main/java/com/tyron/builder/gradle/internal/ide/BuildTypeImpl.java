package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.BuildType;
import com.tyron.builder.model.SigningConfig;
import java.io.Serializable;
import java.util.Objects;

/**
 * Implementation of BuildType that is serializable. Objects used in the DSL cannot be
 * serialized.
 */
@Immutable
final class BuildTypeImpl extends BaseConfigImpl implements BuildType, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final String name;
    private final boolean debuggable;
    private final boolean testCoverageEnabled;
    private final boolean jniDebuggable;
    private final boolean pseudoLocalesEnabled;
    private final boolean renderscriptDebuggable;
    private final int renderscriptOptimLevel;
    @Nullable
    private final String versionNameSuffix;
    private final boolean minifyEnabled;
    private final boolean zipAlignEnabled;
    private final boolean embedMicroApp;

    BuildTypeImpl(@NonNull BuildType buildType) {
        super(buildType);
        this.name = buildType.getName();
        this.debuggable =  buildType.isDebuggable();
        this.testCoverageEnabled = buildType.isTestCoverageEnabled();
        this.jniDebuggable = buildType.isJniDebuggable();
        this.pseudoLocalesEnabled = buildType.isPseudoLocalesEnabled();
        this.renderscriptDebuggable = buildType.isRenderscriptDebuggable();
        this.renderscriptOptimLevel = buildType.getRenderscriptOptimLevel();
        this.versionNameSuffix = buildType.getVersionNameSuffix();
        this.minifyEnabled = buildType.isMinifyEnabled();
        this.zipAlignEnabled = buildType.isZipAlignEnabled();
        this.embedMicroApp = buildType.isEmbedMicroApp();
    }

    @NonNull
    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isDebuggable() {
        return debuggable;
    }

    @Override
    public boolean isTestCoverageEnabled() {
        return testCoverageEnabled;
    }

    @Override
    public boolean isJniDebuggable() {
        return jniDebuggable;
    }

    @Override
    public boolean isRenderscriptDebuggable() {
        return renderscriptDebuggable;
    }

    @Override
    public boolean isPseudoLocalesEnabled() {
        return pseudoLocalesEnabled;
    }

    @Override
    public int getRenderscriptOptimLevel() {
        return renderscriptOptimLevel;
    }

    @Nullable
    @Override
    public String getVersionNameSuffix() {
        return versionNameSuffix;
    }

    @Override
    public boolean isMinifyEnabled() {
        return minifyEnabled;
    }

    @Override
    public boolean isZipAlignEnabled() {
        return zipAlignEnabled;
    }

    @Override
    public boolean isEmbedMicroApp() {
        return embedMicroApp;
    }

    @Nullable
    public SigningConfig getSigningConfig() {
        return null;
    }

    @Override
    public String toString() {
        return "BuildTypeImpl{" +
                "name='" + name + '\'' +
                ", debuggable=" + debuggable +
                ", testCoverageEnabled=" + testCoverageEnabled +
                ", jniDebuggable=" + jniDebuggable +
                ", renderscriptDebuggable=" + renderscriptDebuggable +
                ", renderscriptOptimLevel=" + renderscriptOptimLevel +
                ", versionNameSuffix='" + versionNameSuffix + '\'' +
                ", minifyEnabled=" + minifyEnabled +
                ", zipAlignEnabled=" + zipAlignEnabled +
                ", embedMicroApp=" + embedMicroApp +
                "} " + super.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        BuildTypeImpl buildType = (BuildTypeImpl) o;
        return debuggable == buildType.debuggable &&
                testCoverageEnabled == buildType.testCoverageEnabled &&
                jniDebuggable == buildType.jniDebuggable &&
                pseudoLocalesEnabled == buildType.pseudoLocalesEnabled &&
                renderscriptDebuggable == buildType.renderscriptDebuggable &&
                renderscriptOptimLevel == buildType.renderscriptOptimLevel &&
                minifyEnabled == buildType.minifyEnabled &&
                zipAlignEnabled == buildType.zipAlignEnabled &&
                embedMicroApp == buildType.embedMicroApp &&
                Objects.equals(name, buildType.name) &&
                Objects.equals(versionNameSuffix, buildType.versionNameSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, debuggable, testCoverageEnabled, jniDebuggable,
                pseudoLocalesEnabled, renderscriptDebuggable, renderscriptOptimLevel,
                versionNameSuffix,
                minifyEnabled, zipAlignEnabled, embedMicroApp);
    }
}
