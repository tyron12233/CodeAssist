package org.gradle.android.aapt2;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.util.GUtil;
import org.gradle.api.internal.project.taskfactory.IncrementalTaskAction;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.SourceTask;
import org.gradle.util.internal.GFileUtils;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.InputChanges;
import org.gradle.work.NormalizeLineEndings;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Handles compilation and linking of android resource files incrementally.
 */
public class Aapt2Task extends SourceTask {

    private final FileCollection stableSources = getProject().files((Callable<FileTree>) this::getSource);
    private final RegularFileProperty manifestFile  = getProject().getObjects().fileProperty();
    private final DirectoryProperty outputDirectory = getProject().getObjects().directoryProperty();

    public Aapt2Task() {
        File output = getProject().mkdir(getProject().getBuildDir() + "/intermediates/resources");
        outputDirectory.set(output);

        doLast(new IncrementalTaskAction() {
            @Override
            public void execute(InputChanges inputs) {
                Aapt2Task.this.execute(inputs);
            }
        });
    }

    private Aapt2Runner getAapt2Runner() {
        return getServices().get(Aapt2Runner.class);
    }

    public void execute(InputChanges inputs) {
        Iterable<FileChange> fileChanges = inputs.getFileChanges(getStableSources());
        fileChanges.forEach(this::handleChangedInput);
    }

    private void handleChangedInput(FileChange fileChange) {
        File file = fileChange.getFile();
        ChangeType changeType = fileChange.getChangeType();
        if (changeType == ChangeType.MODIFIED || changeType == ChangeType.REMOVED) {
            GFileUtils.deleteIfExists(
                    new File(
                            outputDirectory.getAsFile().get(),
                            Aapt2RenamingConventions.compilationRename(file)
                    )
            );
        }

        if (changeType == ChangeType.ADDED || changeType == ChangeType.MODIFIED) {
            submitFileToBeCompiled(file);
        }
    }

    private void submitFileToBeCompiled(File file) {
        // create the resource as if AAPT2 compiled it for testing.
        // TODO: handle real compilation
        String compiledName = Aapt2RenamingConventions.compilationRename(file);
        File testOutput = new File(outputDirectory.getAsFile().get(), compiledName);
        GUtil.uncheckedCall(testOutput::createNewFile);
    }


    @Override
    @Internal("tracked via stableSources")
    public FileTree getSource() {
        return super.getSource();
    }

    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @NormalizeLineEndings
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public FileCollection getStableSources() {
        return stableSources;
    }

    @PathSensitive(PathSensitivity.ABSOLUTE)
    @InputFile
    public RegularFileProperty getManifestFile() {
        return manifestFile;
    }

    @OutputDirectory
    public DirectoryProperty getOutputDirectory() {
        return outputDirectory;
    }
}
