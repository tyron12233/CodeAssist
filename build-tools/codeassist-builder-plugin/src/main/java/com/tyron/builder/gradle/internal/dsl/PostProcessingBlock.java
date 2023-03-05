package com.tyron.builder.gradle.internal.dsl;

import static com.tyron.builder.gradle.internal.ProguardFileType.CONSUMER;
import static com.tyron.builder.gradle.internal.ProguardFileType.EXPLICIT;
import static com.tyron.builder.gradle.internal.ProguardFileType.TEST;
import static com.tyron.builder.gradle.internal.dsl.ValidationUtilKt.checkShrinkResourceEligibility;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.dsl.PostProcessing;
import com.tyron.builder.gradle.ProguardFiles;
import com.tyron.builder.gradle.errors.DeprecationReporter;
import com.tyron.builder.gradle.internal.ProguardFileType;
import com.tyron.builder.gradle.internal.ProguardFilesProvider;
import com.tyron.builder.gradle.internal.services.DslServices;
import com.tyron.builder.core.ComponentType;
import com.tyron.builder.model.CodeShrinker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.Incubating;
import org.jetbrains.annotations.NotNull;

/**
 * DSL object for configuring postProcessing: removing dead code, obfuscating etc.
 *
 * <p>This DSL is incubating and subject to change. To configure code and resource shrinkers,
 * Instead use the properties already available in the <a
 * href="com.tyron.builder.gradle.internal.dsl.BuildType.html"><code>buildType</code></a> block.
 *
 * <p>To learn more, read <a
 * href="https://developer.android.com/studio/build/shrink-code.html">Shrink Your Code and
 * Resources</a>.
 */
@Incubating
public class PostProcessingBlock implements ProguardFilesProvider, PostProcessing {
    @NonNull private final DslServices dslServices;
    @NonNull private final ComponentType componentType;

    private boolean removeUnusedCode = true;
    private boolean removeUnusedResources;
    private boolean obfuscate;
    private boolean optimizeCode;

    private List<File> proguardFiles;
    private List<File> testProguardFiles;
    private List<File> consumerProguardFiles;

    @Inject
    public PostProcessingBlock(
            @NonNull DslServices dslServices, @NonNull ComponentType componentType) {
        this(
                dslServices,
                componentType,
                ImmutableList.of(
                        ProguardFiles.getDefaultProguardFile(
                                ProguardFiles.ProguardFile.NO_ACTIONS.fileName,
                                dslServices.getBuildDirectory())));
    }

    @VisibleForTesting
    PostProcessingBlock(
            @NonNull DslServices dslServices,
            @NonNull ComponentType componentType,
            List<File> proguardFiles) {
        this.dslServices = dslServices;
        this.componentType = componentType;
        this.proguardFiles = Lists.newArrayList(proguardFiles);
        this.testProguardFiles = new ArrayList<>();
        this.consumerProguardFiles = new ArrayList<>();
    }

    public void initWith(PostProcessingBlock that) {
        this.removeUnusedCode = that.isRemoveUnusedCode();
        this.removeUnusedResources = that.isRemoveUnusedResources();
        this.obfuscate = that.isObfuscate();
        this.optimizeCode = that.isOptimizeCode();
        this.proguardFiles = Lists.newArrayList(that.getProguardFiles(EXPLICIT));
        this.testProguardFiles = Lists.newArrayList(that.getProguardFiles(TEST));
        this.consumerProguardFiles = Lists.newArrayList(that.getProguardFiles(CONSUMER));
    }

    @Override
    public void initWith(@NotNull PostProcessing that) {
        initWith((PostProcessingBlock) that);
    }

    @Override
    public boolean isRemoveUnusedCode() {
        return removeUnusedCode;
    }

    @Override
    public void setRemoveUnusedCode(boolean removeUnusedCode) {
        this.removeUnusedCode = removeUnusedCode;
    }

    public boolean isRemoveUnusedResources() {
        return removeUnusedResources;
    }

    public void setRemoveUnusedResources(boolean removeUnusedResources) {
        checkShrinkResourceEligibility(componentType, dslServices, removeUnusedResources);
        this.removeUnusedResources = removeUnusedResources;
    }

    public boolean isObfuscate() {
        return obfuscate;
    }

    public void setObfuscate(boolean obfuscate) {
        this.obfuscate = obfuscate;
    }

    public boolean isOptimizeCode() {
        return optimizeCode;
    }

    public void setOptimizeCode(boolean optimizeCode) {
        this.optimizeCode = optimizeCode;
    }

    public void setProguardFiles(List<?> proguardFiles) {
        this.proguardFiles = new ArrayList<>();
        for (Object file : proguardFiles) {
            this.proguardFiles.add(dslServices.file(file));
        }
    }

    public void proguardFile(Object file) {
        this.proguardFiles.add(dslServices.file(file));
    }

    public void proguardFiles(Object... files) {
        for (Object file : files) {
            proguardFile(file);
        }
    }

    public void setTestProguardFiles(List<?> testProguardFiles) {
        this.testProguardFiles = new ArrayList<>();
        for (Object file : testProguardFiles) {
            this.testProguardFiles.add(dslServices.file(file));
        }
    }

    public void testProguardFile(Object file) {
        this.testProguardFiles.add(dslServices.file(file));
    }

    public void testProguardFiles(Object... files) {
        for (Object file : files) {
            testProguardFile(file);
        }
    }

    public void setConsumerProguardFiles(List<?> consumerProguardFiles) {
        this.consumerProguardFiles = new ArrayList<>();
        for (Object file : consumerProguardFiles) {
            this.consumerProguardFiles.add(dslServices.file(file));
        }
    }

    public void consumerProguardFile(Object file) {
        this.consumerProguardFiles.add(dslServices.file(file));
    }

    public void consumerProguardFiles(Object... files) {
        for (Object file : files) {
            consumerProguardFile(file);
        }
    }

    @NonNull
    public String getCodeShrinker() {
        dslServices
                .getDeprecationReporter()
                .reportObsoleteUsage(
                        "codeShrinker", DeprecationReporter.DeprecationTarget.VERSION_8_0);
        return CodeShrinker.R8.name();
    }

    public void setCodeShrinker(@NonNull String name) {
        dslServices
                .getDeprecationReporter()
                .reportObsoleteUsage(
                        "codeShrinker", DeprecationReporter.DeprecationTarget.VERSION_8_0);
    }

    /** For Gradle code, not to be used in the DSL. */
    @Nullable
    public CodeShrinker getCodeShrinkerEnum() {
        return CodeShrinker.R8;
    }

    @NonNull
    @Override
    public Collection<File> getProguardFiles(@NonNull ProguardFileType type) {
        switch (type) {
            case EXPLICIT:
                return proguardFiles;
            case TEST:
                return testProguardFiles;
            case CONSUMER:
                return consumerProguardFiles;
            default:
                throw new AssertionError("Invalid ProguardFileType: " + type);
        }
    }
}
