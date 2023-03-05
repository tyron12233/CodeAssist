package com.tyron.builder.gradle.internal.tasks;

import static com.tyron.builder.internal.utils.HasConfigurableValuesKt.setDisallowChanges;

import com.android.annotations.NonNull;
import com.tyron.builder.gradle.ProguardFiles;
import com.tyron.builder.gradle.ProguardFiles.ProguardFile;
import com.tyron.builder.gradle.internal.component.ConsumableCreationConfig;
import com.tyron.builder.gradle.internal.scope.InternalArtifactType;
import com.tyron.builder.gradle.internal.tasks.factory.VariantTaskCreationAction;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.VERIFICATION)
public abstract class CheckProguardFiles extends NonIncrementalTask {

    @Override
    protected void doTaskAction() {
        // Below we assume new postprocessing DSL is used, since otherwise TaskManager does not
        // create this task.

        Map<File, ProguardFile> oldFiles = new HashMap<>();
        oldFiles.put(
                ProguardFiles.getDefaultProguardFile(
                                ProguardFile.OPTIMIZE.fileName, getBuildDirectory())
                        .getAbsoluteFile(),
                ProguardFile.OPTIMIZE);
        oldFiles.put(
                ProguardFiles.getDefaultProguardFile(
                                ProguardFile.DONT_OPTIMIZE.fileName, getBuildDirectory())
                        .getAbsoluteFile(),
                ProguardFile.DONT_OPTIMIZE);

        for (RegularFile regularFile : getProguardFiles().get()) {
            File file = regularFile.getAsFile();
            if (oldFiles.containsKey(file.getAbsoluteFile())) {
                String name = oldFiles.get(file.getAbsoluteFile()).fileName;
                throw new InvalidUserDataException(
                        name
                                + " should not be used together with the new postprocessing DSL. "
                                + "The new DSL includes sensible settings by default, you can override this "
                                + "using `postprocessing { proguardFiles = []}`");
            }
        }
    }

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ListProperty<RegularFile> getProguardFiles();

    // the extracted proguard files are probably also part of the proguardFiles but we need to set
    // the dependency explicitly so Gradle can track it properly.
    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract DirectoryProperty getExtractedProguardFile();

    @Internal("only for task execution")
    public abstract DirectoryProperty getBuildDirectory();

    public static class CreationAction
            extends VariantTaskCreationAction<CheckProguardFiles, ConsumableCreationConfig> {

        public CreationAction(@NonNull ConsumableCreationConfig creationConfig) {
            super(creationConfig);
        }

        @NonNull
        @Override
        public String getName() {
            return computeTaskName("check", "ProguardFiles");
        }

        @NonNull
        @Override
        public Class<CheckProguardFiles> getType() {
            return CheckProguardFiles.class;
        }

        @Override
        public void configure(@NonNull CheckProguardFiles task) {
            super.configure(task);

            task.getProguardFiles().set(creationConfig.getProguardFiles());
            task.getExtractedProguardFile()
                    .set(
                            creationConfig
                                    .getGlobal()
                                    .getGlobalArtifacts()
                                    .get(InternalArtifactType.DEFAULT_PROGUARD_FILES.INSTANCE));
            task.getProguardFiles().disallowChanges();
            setDisallowChanges(
                    task.getBuildDirectory(), task.getProject().getLayout().getBuildDirectory());
        }
    }
}
