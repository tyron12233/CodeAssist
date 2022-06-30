package com.tyron.builder.plugin.tasks;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.artifacts.repositories.IvyArtifactRepository;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.file.FileVisitDetails;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.InputFiles;
import com.tyron.builder.api.tasks.OutputDirectory;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.SkipWhenEmpty;
import com.tyron.builder.api.tasks.SourceTask;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.api.tasks.compile.JavaCompile;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.util.internal.GUtil;
import com.tyron.builder.work.ChangeType;
import com.tyron.builder.work.FileChange;
import com.tyron.builder.work.InputChanges;
import com.tyron.builder.work.NormalizeLineEndings;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.util.Enumeration;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public class TransformAnnotationProcessorsTask extends SourceTask {

    private final FileCollection stableSources =
            getProject().files((Callable<FileTree>) this::getSource);
    private final File outputDirectory =
            getProject().file(getProject().getBuildDir() + "/transform/annotationProcessors");

    @TaskAction
    public void transform(InputChanges inputs) {
        Iterable<FileChange> fileChanges = inputs.getFileChanges(getStableSources());
        fileChanges.forEach(fileChange -> {
            ChangeType changeType = fileChange.getChangeType();
            if (changeType == ChangeType.REMOVED || changeType == ChangeType.MODIFIED) {
                GFileUtils.forceDelete(getDexEquivalent(fileChange.getFile()));
                if (changeType == ChangeType.REMOVED) {
                    return;
                }
            }

            transform(fileChange.getFile());
        });
    }

    private void transform(File jarFile) {
        File dexEquivalent = getDexEquivalent(jarFile);
        GUtil.uncheckedCall(() -> {

            File temporaryDir = getTemporaryDir();

            D8Command command = D8Command.builder()
                    .addProgramFiles(jarFile.toPath())
                    .setDisableDesugaring(true)
                    .setMinApiLevel(26)
                    .setOutput(temporaryDir.toPath(), OutputMode.DexIndexed)
                    .build();
            D8.run(command);

            File[] dexFiles = temporaryDir.listFiles(c -> c.getName().endsWith(".dex"));

            if (dexFiles == null) {
                throw new BuildException("No dex files found in " + temporaryDir);
            }



            boolean newFile = dexEquivalent.createNewFile();
            if (!newFile) {
                throw new IOException("Failed to create " + dexEquivalent);
            }

            JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(dexEquivalent));
            for (File dexFile : dexFiles) {
                JarEntry entry = new JarEntry(dexFile.getName());
                jarOutputStream.putNextEntry(entry);

                jarOutputStream.write(FileUtils.readFileToByteArray(dexFile));

                jarOutputStream.closeEntry();
            }

            try (JarFile inputFile = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = inputFile.entries();
                while (entries.hasMoreElements()) {
                   JarEntry entry = entries.nextElement();
                   JarEntry newEntry = new JarEntry(entry.getName());
                   jarOutputStream.putNextEntry(newEntry);

                   InputStream inputStream = inputFile.getInputStream(entry);
                   jarOutputStream.write(IOUtils.toByteArray(inputStream));

                   jarOutputStream.closeEntry();
                }
            }

            jarOutputStream.close();
            return dexEquivalent;
        });
    }

    private File getDexEquivalent(File jar) {
        String jarName = jar.getName();
        return new File(outputDirectory, jarName);
    }

    @OutputDirectory
    public File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * The sources for incremental change detection.
     *
     * @since 6.0
     */
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @NormalizeLineEndings
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    protected FileCollection getStableSources() {
        return stableSources;
    }
}
