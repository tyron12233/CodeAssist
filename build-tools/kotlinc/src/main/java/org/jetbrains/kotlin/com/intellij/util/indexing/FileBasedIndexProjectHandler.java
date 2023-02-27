package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.MissingResourceException;

public final class FileBasedIndexProjectHandler {

    @ApiStatus.Internal
    public static final int ourMinFilesToStartDumbMode = intValue("ide.dumb.mode.minFilesToStart", 20);
    private static final int ourMinFilesSizeToStartDumbMode = intValue("ide.dumb.mode.minFilesSizeToStart", 1048576);

    private static int intValue(String key, int defaultValue) {
        try {
            return Registry.intValue(key);
        } catch (MissingResourceException e) {
            return defaultValue;
        }
    }

    public static void scheduleReindexingInDumbMode(@NotNull Project project) {
        final FileBasedIndex i = FileBasedIndex.getInstance();
        if (i instanceof CoreFileBasedIndex &&
            IndexInfrastructure.hasIndices() &&
            !project.isDisposed() &&
            mightHaveManyChangedFilesInProject(project)) {

            String indexingReason = "On refresh of files in " + project.getName();
            new UnindexedFilesIndexer(project, indexingReason).queue(project);
        }
    }

    @ApiStatus.Internal
    public static boolean mightHaveManyChangedFilesInProject(Project project) {
        FileBasedIndex fileBasedIndex = FileBasedIndex.getInstance();
        if (!(fileBasedIndex instanceof CoreFileBasedIndex)) return false;
        long start = System.currentTimeMillis();
        return !((CoreFileBasedIndex)fileBasedIndex).processChangedFiles(project, new Processor<VirtualFile>() {
            int filesInProjectToBeIndexed;
            long sizeOfFilesToBeIndexed;

            @Override
            public boolean process(VirtualFile file) {
                ++filesInProjectToBeIndexed;
                if (file.isValid() && !file.isDirectory()) sizeOfFilesToBeIndexed += file.getLength();
                return filesInProjectToBeIndexed < ourMinFilesToStartDumbMode &&
                       sizeOfFilesToBeIndexed < ourMinFilesSizeToStartDumbMode &&
                       System.currentTimeMillis() < start + 100;
            }
        });
    }

}
