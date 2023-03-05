package com.tyron.builder.gradle.internal.packaging;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.internal.signing.SigningConfigData;
import com.tyron.builder.gradle.internal.signing.SigningConfigVersions;
import com.tyron.builder.files.RelativeFile;
import com.tyron.builder.files.SerializableChange;
import com.tyron.builder.internal.packaging.ApkCreatorType;
import com.tyron.builder.internal.packaging.IncrementalPackager;
import com.android.ide.common.resources.FileStatus;
import com.android.ide.common.signing.CertificateInfo;
import com.android.ide.common.signing.KeystoreHelper;
import com.android.ide.common.signing.KeytoolException;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zfile.ApkCreatorFactory;
import com.android.tools.build.apkzlib.zfile.NativeLibrariesPackagingMode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Factory class to create instances of {@link IncrementalPackager}. Since there are many options
 * for {@link IncrementalPackager} and not all are always required, this makes building the packager
 * easier.
 *
 * <p>While some parameters have sensible defaults, some parameters must be defined. See the
 * {@link #build()} method for information on which parameters are mandatory.
 */
public class IncrementalPackagerBuilder {

    /**
     * Type of build that invokes the instance of IncrementalPackagerBuilder
     *
     * <p>This information is provided as a hint for possible performance optimizations
     */
    public enum BuildType {
        UNKNOWN,
        CLEAN,
        INCREMENTAL
    }

    /** Builder for the data to create APK file. */
    @NonNull
    private ApkCreatorFactory.CreationData.Builder creationDataBuilder =
            ApkCreatorFactory.CreationData.builder();

    /**
     * How should native libraries be packaged. Only applicable if using apkzlib.
     */
    @Nullable private NativeLibrariesPackagingMode nativeLibrariesPackagingMode;

    /** The no-compress predicate: returns {@code true} for paths that should not be compressed. */
    @Nullable private Predicate<String> noCompressPredicate;

    /**
     * Directory for intermediate contents.
     */
    @Nullable
    private File intermediateDir;

    /**
     * Is the build debuggable?
     */
    private boolean debuggableBuild;

    /**
     * Will APK entries be ordered deterministically?
     */
    private boolean deterministicEntryOrder = true;

    /**
     * Is v3 signing enabled?
     */
    private boolean enableV3Signing = false;

    /**
     * Is v4 signing enabled?
     */
    private boolean enableV4Signing = false;

    /**
     * Is the build JNI-debuggable?
     */
    private boolean jniDebuggableBuild;

    /**
     * ABI filters. Empty if none.
     */
    @NonNull
    private Set<String> abiFilters;

    @NonNull private BuildType buildType;

    @NonNull private ApkCreatorType apkCreatorType = ApkCreatorType.APK_Z_FILE_CREATOR;

    @NonNull private Map<RelativeFile, FileStatus> changedDexFiles = new HashMap<>();
    @NonNull private Map<RelativeFile, FileStatus> changedJavaResources = new HashMap<>();
    @NonNull private List<SerializableChange> changedAssets = new ArrayList<>();
    @NonNull private Map<RelativeFile, FileStatus> changedAndroidResources = new HashMap<>();
    @NonNull private Map<RelativeFile, FileStatus> changedNativeLibs = new HashMap<>();
    @NonNull private List<SerializableChange> changedAppMetadata = new ArrayList<>();
    @NonNull private List<SerializableChange> changedArtProfile = new ArrayList<>();
    @NonNull private List<SerializableChange> changedArtProfileMetadata = new ArrayList<>();

    /** Creates a new builder. */
    public IncrementalPackagerBuilder(@NonNull BuildType buildType) {
        abiFilters = new HashSet<>();
        this.buildType = buildType;
        creationDataBuilder.setIncremental(buildType == BuildType.INCREMENTAL);
    }

    /**
     * Sets the signing configuration information for the incremental packager.
     *
     * @param signingConfig the signing config; if {@code null} then the APK will not be signed
     * @param minSdk the minimum SDK
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withSigning(
            @Nullable SigningConfigData signingConfig,
            @NonNull SigningConfigVersions signingConfigVersions,
            int minSdk,
            @Nullable byte[] sdkDependencyData) {
        if (signingConfig == null) {
            return this;
        }
        try {
            String error =
                    "SigningConfig \""
                            + signingConfig.getName()
                            + "\" is missing required property \"%s\".";
            CertificateInfo certificateInfo =
                    KeystoreHelper.getCertificateInfo(
                            signingConfig.getStoreType(),
                            Preconditions.checkNotNull(
                                    signingConfig.getStoreFile(), error, "storeFile"),
                            Preconditions.checkNotNull(
                                    signingConfig.getStorePassword(), error, "storePassword"),
                            Preconditions.checkNotNull(
                                    signingConfig.getKeyPassword(), error, "keyPassword"),
                            Preconditions.checkNotNull(
                                    signingConfig.getKeyAlias(), error, "keyAlias"));

            boolean enableV1Signing = signingConfigVersions.getEnableV1Signing();
            boolean enableV2Signing = signingConfigVersions.getEnableV2Signing();
            enableV3Signing = signingConfigVersions.getEnableV3Signing();
            enableV4Signing = signingConfigVersions.getEnableV4Signing();

            // Check that v2 or v3 signing is enabled if v4 signing is enabled.
            if (enableV4Signing) {
                Preconditions.checkState(
                        enableV2Signing || enableV3Signing,
                        "V4 signing enabled, but v2 and v3 signing are not enabled. V4 signing " +
                                "requires either v2 or v3 signing."
                );
            }

            creationDataBuilder.setSigningOptions(
                    SigningOptions.builder()
                            .setKey(certificateInfo.getKey())
                            .setCertificates(certificateInfo.getCertificate())
                            .setV1SigningEnabled(enableV1Signing)
                            .setV2SigningEnabled(enableV2Signing)
                            .setMinSdkVersion(minSdk)
                            .setValidation(computeValidation())
                            .setSdkDependencyData(sdkDependencyData)
                            .setExecutor(
                                    provider -> {
                                        // noinspection CommonForkJoinPool
                                        ForkJoinPool forkJoinPool = ForkJoinPool.commonPool();
                                        try {
                                            int jobCount = forkJoinPool.getParallelism();
                                            List<Future<?>> jobs = new ArrayList<>(jobCount);

                                            for (int i = 0; i < jobCount; i++) {
                                                jobs.add(
                                                        forkJoinPool.submit(
                                                                provider.createRunnable()));
                                            }

                                            for (Future<?> future : jobs) {
                                                future.get();
                                            }
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            throw new RuntimeException(e);
                                        } catch (ExecutionException e) {
                                            throw new RuntimeException(e);
                                        }
                                    })
                            .build());
        } catch (KeytoolException|FileNotFoundException e) {
            throw new RuntimeException(e);
        }

        return this;
    }

    private SigningOptions.Validation computeValidation() {
        switch (buildType) {
            case INCREMENTAL:
                return SigningOptions.Validation.ASSUME_VALID;
            case CLEAN:
                return SigningOptions.Validation.ASSUME_INVALID;
            case UNKNOWN:
                return SigningOptions.Validation.ALWAYS_VALIDATE;
            default:
                throw new RuntimeException(
                        "Unknown IncrementalPackagerBuilder build type " + buildType);
        }
    }

    /**
     * Sets the output file for the APK.
     *
     * @param f the output file
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withOutputFile(@NonNull File f) {
        creationDataBuilder.setApkPath(f);
        return this;
    }

    /**
     * Sets the packaging mode for native libraries. Only applicable if using apkzlib.
     *
     * TODO (b/134585392) Remove this after removing apkzlib dependency
     *
     * @param packagingMode the packging mode
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withNativeLibraryPackagingMode(
            @NonNull NativeLibrariesPackagingMode packagingMode) {
        nativeLibrariesPackagingMode = packagingMode;
        return this;
    }

    /**
     * Sets the no-compress predicate. This predicate returns {@code true} for files that should
     * not be compressed
     *
     * @param noCompressPredicate the predicate
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withNoCompressPredicate(
            @NonNull Predicate<String> noCompressPredicate) {
        this.noCompressPredicate = noCompressPredicate;
        return this;
    }

    /**
     * Sets the intermediate directory used to store information for incremental builds.
     *
     * @param intermediateDir the intermediate directory
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withIntermediateDir(@NonNull File intermediateDir) {
        this.intermediateDir = intermediateDir;
        return this;
    }

    /**
     * Sets the created-by parameter.
     *
     * @param createdBy the optional value for created-by
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withCreatedBy(@Nullable String createdBy) {
        creationDataBuilder.setCreatedBy(createdBy);
        return this;
    }

    /**
     * Sets whether the build is debuggable or not.
     *
     * @param debuggableBuild is the build debuggable?
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withDebuggableBuild(boolean debuggableBuild) {
        this.debuggableBuild = debuggableBuild;
        return this;
    }

    /**
     * Sets whether the APK entries will be ordered deterministically.
     *
     * @param deterministicEntryOrder will the APK entries be ordered deterministically?
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withDeterministicEntryOrder(boolean deterministicEntryOrder) {
        this.deterministicEntryOrder = deterministicEntryOrder;
        return this;
    }

    /**
     * Sets whether the build is JNI-debuggable or not.
     *
     * @param jniDebuggableBuild is the build JNI-debuggable?
     * @return {@code this} for use with fluent-style notation
     */
    @NonNull
    public IncrementalPackagerBuilder withJniDebuggableBuild(boolean jniDebuggableBuild) {
        this.jniDebuggableBuild = jniDebuggableBuild;
        return this;
    }

    /**
     * Sets the set of accepted ABIs.
     *
     * @param acceptedAbis the accepted ABIs; if empty then all ABIs are accepted
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withAcceptedAbis(@NonNull Set<String> acceptedAbis) {
        this.abiFilters = ImmutableSet.copyOf(acceptedAbis);
        return this;
    }

    /**
     * Sets the {@link ApkCreatorType}
     *
     * @param apkCreatorType the {@link ApkCreatorType}
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withApkCreatorType(@NonNull ApkCreatorType apkCreatorType) {
        this.apkCreatorType = apkCreatorType;
        return this;
    }

    /**
     * Sets the changed dex files
     *
     * @param changedDexFiles the changed dex files
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withChangedDexFiles(
            @NonNull Map<RelativeFile, FileStatus> changedDexFiles) {
        this.changedDexFiles = ImmutableMap.copyOf(changedDexFiles);
        return this;
    }

    /**
     * Sets the changed java resources
     *
     * @param changedJavaResources the changed java resources
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withChangedJavaResources(
            @NonNull Map<RelativeFile, FileStatus> changedJavaResources) {
        this.changedJavaResources = ImmutableMap.copyOf(changedJavaResources);
        return this;
    }

    /**
     * Sets the changed assets
     *
     * @param changedAssets the changed assets
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withChangedAssets(
            @NonNull Collection<SerializableChange> changedAssets) {
        this.changedAssets = ImmutableList.copyOf(changedAssets);
        return this;
    }

    /**
     * Sets the changed android resources
     *
     * @param changedAndroidResources the changed android resources
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withChangedAndroidResources(
            @NonNull Map<RelativeFile, FileStatus> changedAndroidResources) {
        this.changedAndroidResources = ImmutableMap.copyOf(changedAndroidResources);
        return this;
    }

    /**
     * Sets the changed native libs
     *
     * @param changedNativeLibs the changed native libs
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withChangedNativeLibs(
            @NonNull Map<RelativeFile, FileStatus> changedNativeLibs) {
        this.changedNativeLibs = ImmutableMap.copyOf(changedNativeLibs);
        return this;
    }

    /**
     * Sets the changed app metadata
     *
     * @param changedAppMetadata the changed app metadata
     * @return {@code this} for use with fluent-style notation
     */
    public IncrementalPackagerBuilder withChangedAppMetadata(
            @NonNull Collection<SerializableChange> changedAppMetadata) {
        this.changedAppMetadata = ImmutableList.copyOf(changedAppMetadata);
        return this;
    }

    public IncrementalPackagerBuilder withChangedArtProfile(
            @NonNull Collection<SerializableChange> changedMergedProfile) {
        this.changedArtProfile = ImmutableList.copyOf(changedMergedProfile);
        return this;
    }

    public IncrementalPackagerBuilder withChangedArtProfileMetadata(
            @NonNull Collection<SerializableChange> changedArtProfileMetadata) {
        this.changedArtProfileMetadata = ImmutableList.copyOf(changedArtProfileMetadata);
        return this;
    }

    /**
     * Creates the packager, verifying that all the minimum data has been provided. The required
     * information are:
     *
     * <ul>
     *    <li>{@link #withOutputFile(File)}
     *    <li>{@link #withIntermediateDir(File)}
     *    <li>{@link #withNoCompressPredicate(Predicate)}
     * </ul>
     *
     * @return the incremental packager
     */
    @NonNull
    public IncrementalPackager build() {
        Preconditions.checkState(intermediateDir != null, "intermediateDir == null");

        Preconditions.checkNotNull(nativeLibrariesPackagingMode);
        Preconditions.checkNotNull(noCompressPredicate);
        creationDataBuilder
                .setNativeLibrariesPackagingMode(nativeLibrariesPackagingMode)
                .setNoCompressPredicate(noCompressPredicate::test);

        try {
            return new IncrementalPackager(
                    creationDataBuilder.build(),
                    intermediateDir,
                    ApkCreatorFactories.fromProjectProperties(debuggableBuild),
                    abiFilters,
                    jniDebuggableBuild,
                    debuggableBuild,
                    deterministicEntryOrder,
                    enableV3Signing,
                    enableV4Signing,
                    apkCreatorType,
                    changedDexFiles,
                    changedJavaResources,
                    changedAssets,
                    changedAndroidResources,
                    changedNativeLibs,
                    changedAppMetadata,
                    changedArtProfile,
                    changedArtProfileMetadata);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
