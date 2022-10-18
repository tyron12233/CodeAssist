package org.gradle.api.internal.resources;

import com.google.common.io.Files;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.collections.LazilyInitializedFileCollection;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.internal.tasks.TaskDependencyResolveContext;
import org.gradle.api.tasks.util.PatternSet;

import java.io.File;
import java.nio.charset.Charset;

public class FileCollectionBackedArchiveTextResource extends FileCollectionBackedTextResource {
    public FileCollectionBackedArchiveTextResource(final FileOperations fileOperations,
                                                   final TemporaryFileProvider tempFileProvider,
                                                   final FileCollection fileCollection,
                                                   final String path, Charset charset) {
        super(tempFileProvider, new LazilyInitializedFileCollection() {
            @Override
            public String getDisplayName() {
                return String.format("entry '%s' in archive %s", path, fileCollection);
            }

            @Override
            public FileCollection createDelegate() {
                File archiveFile = fileCollection.getSingleFile();
                String fileExtension = Files.getFileExtension(archiveFile.getName());
                FileTree archiveContents = fileExtension.equals("jar") || fileExtension.equals("zip")
                    ? fileOperations.zipTree(archiveFile) : fileOperations.tarTree(archiveFile);
                PatternSet patternSet = new PatternSet();
                patternSet.include(path);
                return archiveContents.matching(patternSet);
            }

            @Override
            public void visitDependencies(TaskDependencyResolveContext context) {
                context.add(fileCollection);
            }
        }, charset);
    }
}
