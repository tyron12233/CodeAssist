package com.tyron.builder.gradle.tasks;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.tyron.builder.api.variant.impl.VariantOutputConfigurationImplKt;
import com.tyron.builder.api.variant.impl.VariantOutputImpl;
import com.tyron.builder.gradle.internal.component.ComponentCreationConfig;
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig;
import com.android.utils.FileUtils;
import com.google.common.base.Preconditions;
import com.tyron.builder.tasks.IncrementalTask;

import java.io.File;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.work.Incremental;

/** Base class for process resources / create R class task, to satisfy existing variants API. */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.ANDROID_RESOURCES)
public abstract class ProcessAndroidResources extends IncrementalTask {

    protected VariantOutputImpl mainSplit;

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    @Incremental
    public abstract DirectoryProperty getAaptFriendlyManifestFiles();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @Incremental
    public abstract DirectoryProperty getManifestFiles();

    // This input in not required for the task to function properly.
    // However, the implementation of getManifestFile() requires it to stay compatible with past
    // plugin and crashlitics related plugins are using it.
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    @Deprecated
    @Incremental
    public abstract DirectoryProperty getMergedManifestFiles();

    // Used by the kotlin plugin.
    // Subclasses of this class should also declare this method as @Internal and have a separate
    // method/field that declares the output.
    @Internal
    @Deprecated
    public abstract File getSourceOutputDir();

    @Internal // getManifestFiles() is already marked as @InputFiles
    @Deprecated
    public File getManifestFile() {
        File manifestDirectory;
        if (getAaptFriendlyManifestFiles().isPresent()) {
            manifestDirectory = getAaptFriendlyManifestFiles().get().getAsFile();
        } else {
            if (getMergedManifestFiles().isPresent()) {
                manifestDirectory = getMergedManifestFiles().get().getAsFile();
            } else {
                manifestDirectory = getManifestFiles().get().getAsFile();
            }
        }
        Preconditions.checkNotNull(manifestDirectory);

        Preconditions.checkNotNull(mainSplit);
        return FileUtils.join(
                manifestDirectory,
                VariantOutputConfigurationImplKt.dirName(mainSplit),
                SdkConstants.ANDROID_MANIFEST_XML);
    }

    protected static boolean generatesProguardOutputFile(
            @NonNull ComponentCreationConfig creationConfig) {
        return (creationConfig instanceof ConsumableCreationConfig
                        && ((ConsumableCreationConfig) creationConfig).getMinifiedEnabled())
                || creationConfig.getComponentType().isDynamicFeature();
    }
}
