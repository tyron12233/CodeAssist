package com.tyron.builder.android.aapt2;

import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.util.GUtil;
import com.tyron.builder.api.internal.project.taskfactory.IncrementalTaskAction;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.InputFile;
import com.tyron.builder.api.tasks.InputFiles;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.OutputDirectory;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.SkipWhenEmpty;
import com.tyron.builder.api.tasks.SourceTask;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.work.ChangeType;
import com.tyron.builder.work.FileChange;
import com.tyron.builder.work.InputChanges;
import com.tyron.builder.work.NormalizeLineEndings;

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
