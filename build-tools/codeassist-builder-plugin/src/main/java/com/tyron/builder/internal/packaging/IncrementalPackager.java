package com.tyron.builder.internal.packaging;

import static com.android.ide.common.resources.FileStatus.CHANGED;
import static com.android.ide.common.resources.FileStatus.REMOVED;
import static java.util.zip.Deflater.BEST_SPEED;
import static java.util.zip.Deflater.DEFAULT_COMPRESSION;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.files.NativeLibraryAbiPredicate;
import com.tyron.builder.files.RelativeFile;
import com.tyron.builder.files.SerializableChange;
import com.android.ide.common.resources.FileStatus;
import com.android.tools.build.apkzlib.zfile.ApkCreator;
import com.android.tools.build.apkzlib.zfile.ApkCreatorFactory;
import com.android.zipflinger.ZipArchive;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import kotlin.text.StringsKt;

/**
 * Makes the final app package. The packager allows build an APK from:
 * <ul>
 *     <li>The package output from aapt;
 *     <li>Java resource files;
 *     <li>JNI libraries;
 *     <li>Dex files;
 * </ul>
 *
 * <p>The {@code IncrementalPackager} class can create an APK from scratch and can incrementally
 * build and APK. To work incrementally with the {@code IncrementalPackager} it is necessary to
 * provide information on which files were externally created, updated or deleted.
 *
 * <p>The {@code IncrementalPackager} allows working with archives (zip files). After an archive is
 * added to the package, the {@code IncrementalPackager} will keep a copy of the added (or updated)
 * archive to allow incremental updates. The semantics for working with archives are:
 *
 * <ul>
 *     <li>Adding an archive is equivalent to add all files in the archive;</li>
 *     <li>Updating an archive is equivalent to add all files that exist in the new version of the
 *     archive, remove all files that no longer exist in the new version in the archive and to
 *     update all files that have changed (the size and CRC checksum of the files in the archive
 *     can be used for fast detection;
 *     <li>Deleting an archive is equivalent to removing all files that exist in last updated
 *     version of the archive;
 * </ul>
 *
 * <p>File caches should be independent of the produced APKs; each produced APK has its
 * intermediate directory. This is required to avoid incorrect updates of incremental APKs. For
 * example, if archive file <i>A</i> used in both APKs <i>x</i> and <i>y</i>, updating <i>A</i>,
 * updating <i>x</i>, updating <i>A</i> again and then updating <i>y</i> would yield an incorrect
 * incremental update, as the difference between the last stored <i>A</i> and the new <i>A</i>
 * does not correctly reflect the changes need to apply to <i>y</i>.
 *
 * <p>{@code IncrementalPackager} places caches inside a provided <i>intermediate directory</i>.
 * {@code IncrementalPackager} provides two ways to ensure independent caches for different APKs.
 * The first is that the directory used for caches is a subdirectory of the provided intermediate
 * directory named after the APK. So, APKs with different names will always use different
 * caches. Secondly, if multiple APKs can exist with different names, then different intermediate
 * directories should be provided for each.
 */
public class IncrementalPackager implements Closeable {

    public static final String APP_METADATA_FILE_NAME = "app-metadata.properties";
    public static final String APP_METADATA_ENTRY_PATH =
            "META-INF/com/android/build/gradle/" + APP_METADATA_FILE_NAME;

    /**
     * {@link ApkCreator}, which is {@code null} until it's initialized via getApkCreator(). Use
     * getApkCreator() instead of accessing this field directly, except in {@link
     * IncrementalPackager::close}, where direct access is appropriate to avoid unnecessary
     * initialization.
     */
    @Nullable private ApkCreator mApkCreator;

    /** {@link ApkCreatorFactory.CreationData} for mApkCreator initialization. */
    @NonNull private final ApkCreatorFactory.CreationData mCreationData;

    /** {@link ApkCreatorFactory} for mApkCreator initialization. */
    @NonNull private final ApkCreatorFactory mApkCreatorFactory;

    /** Whether the build is debuggable, which might influence the compression level. */
    private final boolean mIsDebuggableBuild;

    /** Whether the zip entries will be ordered deterministically. */
    private final boolean mDeterministicEntryOrder;

    /** Whether v3 signing is enabled. */
    private final boolean mEnableV3Signing;

    /** Whether v4 signing is enabled. */
    private final boolean mEnableV4Signing;

    /** Returns mApkCreator, initialized lazily. */
    @NonNull
    private ApkCreator getApkCreator() {
        if (mApkCreator == null) {
            switch (mApkCreatorType) {
                case APK_Z_FILE_CREATOR:
                    Preconditions.checkState(
                            !mEnableV3Signing,
                            ""
                                    + "enableV3Signing cannot be true unless "
                                    + "android.useNewApkCreator is also true.");
                    Preconditions.checkState(
                            !mEnableV4Signing,
                            ""
                                    + "enableV4Signing cannot be true unless "
                                    + "android.useNewApkCreator is also true.");
                    mApkCreator = mApkCreatorFactory.make(mCreationData);
                    break;
                case APK_FLINGER:
                    int compressionLevel = mIsDebuggableBuild ? BEST_SPEED : DEFAULT_COMPRESSION;
                    mApkCreator =
                            new ApkFlinger(
                                    mCreationData,
                                    compressionLevel,
                                    mDeterministicEntryOrder,
                                    mEnableV3Signing,
                                    mEnableV4Signing);
                    break;
                default:
                    throw new RuntimeException("unexpected apkCreatorType");
            }
        }
        return mApkCreator;
    }

    /** False until {@link IncrementalPackager::close} method called, and true thereafter. */
    private boolean mClosed;

    /**
     * APK creator type. We make calls differently depending on {@link ApkCreatorType} because
     * {@link ZipArchive} requires that existing entries be deleted before an entry with the same
     * path is added.
     */
    @NonNull private final ApkCreatorType mApkCreatorType;

    /**
     * Class that manages the renaming of dex files.
     */
    @NonNull
    private final DexIncrementalRenameManager mDexRenamer;

    /**
     * Predicate to filter native libraries.
     */
    @NonNull
    private final NativeLibraryAbiPredicate mAbiPredicate;

    @NonNull private final Map<RelativeFile, FileStatus> mChangedDexFiles;

    @NonNull private final Map<RelativeFile, FileStatus> mChangedJavaResources;

    @NonNull private final List<SerializableChange> mChangedAssets;

    @NonNull private final Map<RelativeFile, FileStatus> mChangedAndroidResources;

    @NonNull private final Map<RelativeFile, FileStatus> mChangedNativeLibs;

    @NonNull private final List<SerializableChange> mChangedAppMetadata;

    @NonNull private final List<SerializableChange> mChangedArtProfile;

    @NonNull private final List<SerializableChange> mChangedArtProfileMetadata;

    /**
     * Creates a new instance.
     *
     * <p>This creates a new builder that will create the specified output file.
     *
     * @param creationData APK creation data
     * @param intermediateDir a directory where to store intermediate files
     * @param factory the factory used to create APK creators
     * @param acceptedAbis the set of accepted ABIs; if empty then all ABIs are accepted
     * @param jniDebugMode is JNI debug mode enabled?
     * @param debuggableBuild is this a debuggable build?
     * @param deterministicEntryOrder will APK entries be ordered deterministically?
     * @param enableV3Signing is v3 signing enabled?
     * @param enableV4Signing is v4 signing enabled?
     * @param apkCreatorType the {@link ApkCreatorType}
     * @param changedDexFiles the changed dex files
     * @param changedJavaResources the changed java resources
     * @param changedAssets the changed assets
     * @param changedAndroidResources the changed android resources
     * @param changedNativeLibs the changed native libraries
     * @param changedAppMetadata the changed app metadata
     * @param changedArtProfile the changed art profile for compose
     * @param changedArtProfileMetadata the changed art profile metadata for compose
     * @throws IOException failed to create the APK
     */
    public IncrementalPackager(
            @NonNull ApkCreatorFactory.CreationData creationData,
            @NonNull File intermediateDir,
            @NonNull ApkCreatorFactory factory,
            @NonNull Set<String> acceptedAbis,
            boolean jniDebugMode,
            boolean debuggableBuild,
            boolean deterministicEntryOrder,
            boolean enableV3Signing,
            boolean enableV4Signing,
            @NonNull ApkCreatorType apkCreatorType,
            @NonNull Map<RelativeFile, FileStatus> changedDexFiles,
            @NonNull Map<RelativeFile, FileStatus> changedJavaResources,
            @NonNull List<SerializableChange> changedAssets,
            @NonNull Map<RelativeFile, FileStatus> changedAndroidResources,
            @NonNull Map<RelativeFile, FileStatus> changedNativeLibs,
            @NonNull List<SerializableChange> changedAppMetadata,
            @NonNull List<SerializableChange> changedArtProfile,
            @NonNull List<SerializableChange> changedArtProfileMetadata)
            throws IOException {
        if (!intermediateDir.isDirectory()) {
            throw new IllegalArgumentException(
                    "!intermediateDir.isDirectory(): " + intermediateDir);
        }
        checkOutputFile(creationData.getApkPath());

        mCreationData = creationData;
        mApkCreatorFactory = factory;
        mIsDebuggableBuild = debuggableBuild;
        mDeterministicEntryOrder = deterministicEntryOrder;
        mEnableV3Signing = enableV3Signing;
        mEnableV4Signing = enableV4Signing;
        mClosed = false;
        mApkCreatorType = apkCreatorType;
        mChangedDexFiles = changedDexFiles;
        mChangedJavaResources = changedJavaResources;
        mChangedAssets = changedAssets;
        mChangedAndroidResources = changedAndroidResources;
        mChangedNativeLibs = changedNativeLibs;
        mChangedAppMetadata = changedAppMetadata;
        mDexRenamer = new DexIncrementalRenameManager(intermediateDir);
        mAbiPredicate = new NativeLibraryAbiPredicate(acceptedAbis, jniDebugMode);
        mChangedArtProfile = changedArtProfile;
        mChangedArtProfileMetadata = changedArtProfileMetadata;
    }

    /**
     * Updates all new, changed, and deleted files in the archive.
     *
     * <p>This method should only be called once.
     *
     * @throws IOException failed to update the archive.
     */
    public void updateFiles() throws IOException {
        // Calculate packagedFileUpdates
        List<PackagedFileUpdate> packagedFileUpdates = new ArrayList<>();
        packagedFileUpdates.addAll(mDexRenamer.update(mChangedDexFiles));
        packagedFileUpdates.addAll(
                PackagedFileUpdates.fromIncrementalRelativeFileSet(
                        Maps.filterKeys(
                                mChangedJavaResources,
                                rf -> !rf.getRelativePath().endsWith(SdkConstants.DOT_CLASS))));
        packagedFileUpdates.addAll(
                PackagedFileUpdates.fromIncrementalRelativeFileSet(mChangedAndroidResources));
        packagedFileUpdates.addAll(
                PackagedFileUpdates.fromIncrementalRelativeFileSet(
                        Maps.filterKeys(
                                mChangedNativeLibs,
                                rf -> mAbiPredicate.test(rf.getRelativePath()))));
        packagedFileUpdates.addAll(getAppMetadataUpdates(mChangedAppMetadata));
        packagedFileUpdates.addAll(getArtProfileUpdates(mChangedArtProfile));
        packagedFileUpdates.addAll(getArtProfileMetadataUpdates(mChangedArtProfileMetadata));

        // First delete all REMOVED (and maybe CHANGED) files, then add all NEW or CHANGED files.
        deleteFiles(packagedFileUpdates);
        updateSingleEntryJars(mChangedAssets);
        addFiles(packagedFileUpdates);
    }

    /**
     * Updates files in the archive
     *
     * @param changes the collection of changes to be applied to the archive. These changes are
     *                assumed to correspond to only single-entry jar files, where each jar file's
     *                normalized path is equal to its entry's name + ".jar" (and each entry name
     *                will be the same as the entry name in the output).
     * @throws IOException failed to update the archive
     */
    private void updateSingleEntryJars(
            @NonNull Collection<SerializableChange> changes) throws IOException {
        Preconditions.checkState(!mClosed, "IncrementalPackager has already been closed.");

        // We first delete all REMOVED (and maybe CHANGED) jars
        Predicate<SerializableChange> deletePredicate =
                mApkCreatorType == ApkCreatorType.APK_FLINGER
                        ? it -> it.getFileStatus() == REMOVED || it.getFileStatus() == CHANGED
                        : it -> it.getFileStatus() == REMOVED;

        Iterable<String> deletedJars =
                changes.stream()
                        .filter(deletePredicate)
                        .map(SerializableChange::getNormalizedPath)
                        .collect(Collectors.toList());

        for (String deletedJar : deletedJars) {
            Preconditions.checkState(deletedJar.endsWith(SdkConstants.DOT_JAR));
            getApkCreator().deleteFile(StringsKt.removeSuffix(deletedJar, SdkConstants.DOT_JAR));
        }

        // We then add all NEW or CHANGED jars
        Predicate<SerializableChange> isNewOrChanged =
                it -> it.getFileStatus() == FileStatus.NEW || it.getFileStatus() == CHANGED;

        Iterable<File> addedJars =
                changes.stream()
                        .filter(isNewOrChanged)
                        .map(SerializableChange::getFile)
                        .collect(Collectors.toList());

        for (File addedJar : addedJars) {
            getApkCreator().writeZip(addedJar, null, null);
        }
    }

    /**
     * Produce a list of app metadata PackagedFileUpdates given a list of app metadata
     * SerializableChanges, which should either be empty or contain a single element.
     *
     * @param changes the collection of app metadata changes
     * @return a corresponding list of PackagedFileUpdates
     */
    private static List<PackagedFileUpdate> getAppMetadataUpdates(
            @NonNull Collection<SerializableChange> changes) {
        return changes.stream()
                .map(
                        change ->
                                new PackagedFileUpdate(
                                        new RelativeFile(
                                                change.getFile().getParentFile(),
                                                change.getFile()),
                                        APP_METADATA_ENTRY_PATH,
                                        change.getFileStatus()))
                .collect(Collectors.toList());
    }

    private static List<PackagedFileUpdate> getArtProfileUpdates(
            @NonNull Collection<SerializableChange> changes) {
        return changes.stream()
                .map(
                        change ->
                                new PackagedFileUpdate(
                                        new RelativeFile(
                                                change.getFile().getParentFile(), change.getFile()),
                                        SdkConstants.FN_BINART_ART_PROFILE_FOLDER_IN_APK
                                                + "/"
                                                + SdkConstants.FN_BINARY_ART_PROFILE,
                                        change.getFileStatus()))
                .collect(Collectors.toList());
    }

    private static List<PackagedFileUpdate> getArtProfileMetadataUpdates(
            @NonNull Collection<SerializableChange> changes) {
        return changes.stream()
                .map(
                        change ->
                                new PackagedFileUpdate(
                                        new RelativeFile(
                                                change.getFile().getParentFile(), change.getFile()),
                                        SdkConstants.FN_BINART_ART_PROFILE_FOLDER_IN_APK
                                                + "/"
                                                + SdkConstants.FN_BINARY_ART_PROFILE_METADATA,
                                        change.getFileStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Deletes files in the archive.
     *
     * @param updates the collection of updates, some of which will be deleted, depending on their
     *     {@link FileStatus}
     * @throws IOException failed to update the archive
     */
    private void deleteFiles(@NonNull Collection<PackagedFileUpdate> updates) throws IOException {
        Preconditions.checkState(!mClosed, "IncrementalPackager has already been closed.");

        Predicate<PackagedFileUpdate> deletePredicate =
                mApkCreatorType == ApkCreatorType.APK_FLINGER
                        ? (p) -> p.getStatus() == REMOVED || p.getStatus() == CHANGED
                        : (p) -> p.getStatus() == REMOVED;

        Iterable<String> deletedPaths =
                updates.stream()
                        .filter(deletePredicate)
                        .map(PackagedFileUpdate::getName)
                        .collect(Collectors.toList());

        for (String deletedPath : deletedPaths) {
            getApkCreator().deleteFile(deletedPath);
        }
    }

    /**
     * Add files to the archive.
     *
     * @param updates the collection of updates, some of which will be added, depending on their
     *     {@link FileStatus}
     * @throws IOException failed to update the archive
     */
    private void addFiles(@NonNull Collection<PackagedFileUpdate> updates) throws IOException {
        Preconditions.checkState(!mClosed, "IncrementalPackager has already been closed.");

        Predicate<PackagedFileUpdate> isNewOrChanged =
                pfu -> pfu.getStatus() == FileStatus.NEW || pfu.getStatus() == CHANGED;

        Iterable<PackagedFileUpdate> newOrChangedNonArchiveFiles =
                updates.stream()
                        .filter(
                                pfu ->
                                        pfu.getSource().getType() == RelativeFile.Type.DIRECTORY
                                                && isNewOrChanged.test(pfu))
                        .collect(Collectors.toList());

        for (PackagedFileUpdate rf : newOrChangedNonArchiveFiles) {
            File out = rf.getSource().getFile();
            getApkCreator().writeFile(out, rf.getName());
        }

        Iterable<PackagedFileUpdate> newOrChangedArchiveFiles =
                updates.stream()
                        .filter(
                                pfu ->
                                        pfu.getSource().getType() == RelativeFile.Type.JAR
                                                && isNewOrChanged.test(pfu))
                        .collect(Collectors.toList());

        Set<File> archives =
                StreamSupport.stream(newOrChangedArchiveFiles.spliterator(), false)
                        .map(pfu -> pfu.getSource().getBase())
                        .collect(Collectors.toSet());
        Set<String> names = Sets.newHashSet(
                Iterables.transform(
                        newOrChangedArchiveFiles,
                        PackagedFileUpdate::getName));

        /*
         * Build the name map. The name of the file in the filesystem (or zip file) may not
         * match the name we want to package it as. See PackagedFileUpdate for more information.
         */
        Map<String, String> pathNameMap = Maps.newHashMap();
        for (PackagedFileUpdate archiveUpdate : newOrChangedArchiveFiles) {
            pathNameMap.put(archiveUpdate.getSource().getRelativePath(), archiveUpdate.getName());
        }

        for (File arch : archives) {
            getApkCreator().writeZip(arch, pathNameMap::get, name -> !names.contains(name));
        }
    }

    /**
     * Checks that output path is a valid file. This will generally provide a friendler error
     * message if the file cannot be created.
     *
     * <p>It checks the following:
     * <ul>
     *     <li>The path is not an existing directory;
     *     <li>if the file exists, it is writeable;
     *     <li>if the file doesn't exists, that a new file can be created in its place
     * </ul>
     *
     * @param file the path to check
     * @throws IOException the check failed
     */
    private static void checkOutputFile(@NonNull File file) throws IOException {
        if (file.isDirectory()) {
            throw new IOException(String.format("'%s' is a directory", file.getAbsolutePath()));
        }

        if (file.exists()) { // will be a file in this case.
            if (!file.canWrite()) {
                throw new IOException(
                        String.format("'%s' is not writeable", file.getAbsolutePath()));
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    throw new IOException(String.format("Failed to create '%s'",
                            file.getAbsolutePath()));
                }

                /*
                 * We succeeded at creating the file. Now, delete it because a zero-byte file is
                 * not a valid APK and some ApkCreator implementations (e.g., the ZFile one)
                 * complain if open on top of an invalid zip file.
                 */
                if (!file.delete()) {
                    throw new IOException(String.format("Failed to delete newly created '%s'",
                            file.getAbsolutePath()));
                }
            } catch (IOException e) {
                throw new IOException(String.format("Failed to create '%s'",
                        file.getAbsolutePath()), e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (mClosed) {
            return;
        }

        try (Closer closer = Closer.create()) {
            // If not incremental, force the initialization of mApkCreator in order to force signing
            // in case creationData.signingOptions has changed.
            if (!mCreationData.isIncremental()) {
                getApkCreator();
            }
            // Use mApkCreator instead of getApkCreator() here because if mApkCreator is null at
            // this point, we don't want to initialize it just to close it.
            closer.register(mApkCreator);
            closer.register(mDexRenamer);
            mClosed = true;
        }
    }
}
