package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.AndroidArtifact;
import com.tyron.builder.model.AndroidArtifactOutput;
import com.tyron.builder.model.ClassField;
import com.tyron.builder.model.CodeShrinker;
import com.tyron.builder.model.Dependencies;
import com.tyron.builder.model.InstantRun;
import com.tyron.builder.model.NativeLibrary;
import com.tyron.builder.model.SourceProvider;
import com.tyron.builder.model.TestOptions;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.io.File;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.gradle.api.file.RegularFile;

/**
 * Implementation of AndroidArtifact that is serializable
 */
@Immutable
final class AndroidArtifactImpl extends BaseArtifactImpl implements AndroidArtifact, Serializable {
    private static final long serialVersionUID = 2L;

    private final boolean isSigned;
    @NonNull private final String baseName;
    @NonNull private final String sourceGenTaskName;
    @NonNull private final List<File> generatedResourceFolders;
    @NonNull private final List<File> additionalRuntimeApks;
    @NonNull private final InstantRun instantRun;
    @Nullable private final String signingConfigName;
    @Nullable private final Set<String> abiFilters;
    @Nullable private final TestOptions testOptions;
    @Nullable private final String instrumentedTestTaskName;
    @Nullable private final String bundleTaskName;
    @Nullable private final String bundleTaskOutputListingFile;
    @Nullable private final String apkFromBundleTaskName;
    @Nullable private final String apkFromBundleTaskOutputListingFile;
    @Nullable private final CodeShrinker codeShrinker;

    AndroidArtifactImpl(
            @NonNull String name,
            @NonNull String baseName,
            @NonNull String assembleTaskName,
            @Nullable RegularFile postAssembleTaskModelFile,
            boolean isSigned,
            @Nullable String signingConfigName,
            @NonNull String sourceGenTaskName,
            @NonNull String compileTaskName,
            @NonNull List<File> generatedSourceFolders,
            @NonNull List<File> generatedResourceFolders,
            @NonNull File classesFolder,
            @NonNull Set<File> additionalClassFolders,
            @NonNull File javaResourcesFolder,
            @NonNull Dependencies compileDependencies,
            @NonNull DependencyGraphs dependencyGraphs,
            @NonNull List<File> additionalRuntimeApks,
            @Nullable SourceProvider variantSourceProvider,
            @Nullable SourceProvider multiFlavorSourceProviders,
            @Nullable Set<String> abiFilters,
            @NonNull InstantRun instantRun,
            @Nullable TestOptions testOptions,
            @Nullable String instrumentedTestTaskName,
            @Nullable String bundleTaskName,
            @Nullable RegularFile bundleTaskOutputListingFile,
            @Nullable String apkFromBundleTaskName,
            @Nullable RegularFile apkFromBundleTaskOutputListingFile,
            @Nullable CodeShrinker codeShrinker) {
        super(
                name,
                assembleTaskName,
                postAssembleTaskModelFile,
                compileTaskName,
                classesFolder,
                additionalClassFolders,
                javaResourcesFolder,
                compileDependencies,
                dependencyGraphs,
                variantSourceProvider,
                multiFlavorSourceProviders,
                generatedSourceFolders);

        this.baseName = baseName;
        this.isSigned = isSigned;
        this.signingConfigName = signingConfigName;
        this.sourceGenTaskName = sourceGenTaskName;
        this.generatedResourceFolders = generatedResourceFolders;
        this.additionalRuntimeApks = additionalRuntimeApks;
        this.abiFilters = abiFilters;
        this.instantRun = instantRun;
        this.testOptions = testOptions;
        this.instrumentedTestTaskName = instrumentedTestTaskName;
        this.bundleTaskName = bundleTaskName;
        this.bundleTaskOutputListingFile =
                bundleTaskOutputListingFile != null
                        ? bundleTaskOutputListingFile.getAsFile().getAbsolutePath()
                        : null;
        this.apkFromBundleTaskName = apkFromBundleTaskName;
        this.apkFromBundleTaskOutputListingFile =
                apkFromBundleTaskOutputListingFile != null
                        ? apkFromBundleTaskOutputListingFile.getAsFile().getAbsolutePath()
                        : null;
        this.codeShrinker = codeShrinker;
    }

    @NonNull
    @Override
    public Collection<AndroidArtifactOutput> getOutputs() {
        throw new RuntimeException("This method has been deprecated in Studio/AGP 4.0");
    }

    @Override
    public boolean isSigned() {
        return isSigned;
    }

    @Nullable
    @Override
    public String getSigningConfigName() {
        return signingConfigName;
    }

    @NonNull
    @Override
    public String getApplicationId() {
        return "";
    }

    @NonNull
    @Override
    public String getSourceGenTaskName() {
        return sourceGenTaskName;
    }

    @NonNull
    @Override
    public Set<String> getIdeSetupTaskNames() {
        return Sets.newHashSet(getSourceGenTaskName());
    }

    @NonNull
    @Override
    public List<File> getGeneratedResourceFolders() {
        return generatedResourceFolders;
    }

    @Nullable
    @Override
    public Set<String> getAbiFilters() {
        return abiFilters;
    }

    @NonNull
    @Override
    public Collection<NativeLibrary> getNativeLibraries() {
        return ImmutableList.of();
    }

    @Override
    @NonNull
    public Map<String, ClassField> getResValues() {
        return Collections.emptyMap();
    }

    @NonNull
    @Override
    public InstantRun getInstantRun() {
        return instantRun;
    }

    @NonNull
    @Override
    public List<File> getAdditionalRuntimeApks() {
        return additionalRuntimeApks;
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
        AndroidArtifactImpl that = (AndroidArtifactImpl) o;
        return isSigned == that.isSigned
                && Objects.equals(signingConfigName, that.signingConfigName)
                && Objects.equals(sourceGenTaskName, that.sourceGenTaskName)
                && Objects.equals(generatedResourceFolders, that.generatedResourceFolders)
                && Objects.equals(abiFilters, that.abiFilters)
                && Objects.equals(instantRun, that.instantRun)
                && Objects.equals(additionalRuntimeApks, that.additionalRuntimeApks)
                && Objects.equals(baseName, that.baseName)
                && Objects.equals(testOptions, that.testOptions)
                && Objects.equals(instrumentedTestTaskName, that.instrumentedTestTaskName)
                && Objects.equals(bundleTaskName, that.bundleTaskName)
                && Objects.equals(bundleTaskOutputListingFile, that.bundleTaskOutputListingFile)
                && Objects.equals(
                        apkFromBundleTaskOutputListingFile, that.apkFromBundleTaskOutputListingFile)
                && Objects.equals(codeShrinker, that.codeShrinker)
                && Objects.equals(apkFromBundleTaskName, that.apkFromBundleTaskName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                isSigned,
                signingConfigName,
                sourceGenTaskName,
                generatedResourceFolders,
                abiFilters,
                instantRun,
                additionalRuntimeApks,
                baseName,
                testOptions,
                instrumentedTestTaskName,
                bundleTaskName,
                bundleTaskOutputListingFile,
                codeShrinker,
                apkFromBundleTaskName,
                apkFromBundleTaskOutputListingFile);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("isSigned", isSigned)
                .add("signingConfigName", signingConfigName)
                .add("applicationId", "")
                .add("sourceGenTaskName", sourceGenTaskName)
                .add("generatedResourceFolders", generatedResourceFolders)
                .add("abiFilters", abiFilters)
                .add("instantRun", instantRun)
                .add("testOptions", testOptions)
                .add("instrumentedTestTaskName", instrumentedTestTaskName)
                .add("bundleTaskName", bundleTaskName)
                .add("bundleTasOutputListingFile", bundleTaskName)
                .add("codeShrinker", codeShrinker)
                .add("apkFromBundleTaskOutputListingFile", apkFromBundleTaskName)
                .toString();
    }

    @Override
    @Nullable
    public TestOptions getTestOptions() {
        return testOptions;
    }

    @Nullable
    @Override
    public String getInstrumentedTestTaskName() {
        return instrumentedTestTaskName;
    }

    @Nullable
    @Override
    public String getBundleTaskName() {
        return bundleTaskName;
    }

    @Nullable
    @Override
    public String getBundleTaskOutputListingFile() {
        return bundleTaskOutputListingFile;
    }

    @Nullable
    @Override
    public String getApkFromBundleTaskName() {
        return apkFromBundleTaskName;
    }

    @Nullable
    @Override
    public String getApkFromBundleTaskOutputListingFile() {
        return apkFromBundleTaskOutputListingFile;
    }

    @Nullable
    @Override
    public CodeShrinker getCodeShrinker() {
        return codeShrinker;
    }
}
