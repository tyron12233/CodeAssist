package com.tyron.code.indexing;

import org.jetbrains.kotlin.com.intellij.openapi.module.Module;
import org.jetbrains.kotlin.com.intellij.openapi.module.ModuleManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.CodeAssistProject;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexableFilesIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.UnindexedFileStatus;
import org.jetbrains.kotlin.com.intellij.util.indexing.UnindexedFilesFinder;
import org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue.IndexUpdateRunner;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesIterator;

import java.util.Collection;
import java.util.Collections;

import kotlin.Unit;

public class ProjectIndexer {

    public static void index(Project project,
                             CoreFileBasedIndex fileBasedIndex) throws IndexUpdateRunner.IndexingInterruptedException {
        fileBasedIndex.registerProjectFileSets(project);
        fileBasedIndex.getIndexableFilesFilterHolder().getProjectIndexableFiles(project);

        UnindexedFilesFinder finder =
                new UnindexedFilesFinder(project, fileBasedIndex, indexedFile -> false);

        for (IndexableFilesIterator indexingIterator : fileBasedIndex.getIndexableFilesProviders(
                project)) {
            indexingIterator.iterateFiles(project, fileOrDir -> {

                UnindexedFileStatus fileStatus = finder.getFileStatus(fileOrDir);
                if (fileStatus != null && fileStatus.isShouldIndex()) {
                    ProgressManager.getInstance().computeInNonCancelableSection(() -> {
                        fileBasedIndex.scheduleFileForIndexing(FileIdStorage.getAndStoreId(fileOrDir),
                                fileOrDir,
                                false);
                        return Unit.INSTANCE;
                    });
                }

                return true;
            }, virtualFile -> true);
        }

        IndexableFilesIndex instance = IndexableFilesIndex.getInstance(project);
        for (Module module : ModuleManager.getInstance(project).getSortedModules()) {
            for (IndexableFilesIterator moduleIndexingIterator :
                    instance.getModuleIndexingIterators(
                    module)) {
                moduleIndexingIterator.iterateFiles(project, fileOrDir -> {
                    UnindexedFileStatus fileStatus = finder.getFileStatus(fileOrDir);
                    if (fileStatus != null && fileStatus.isShouldIndex()) {
                        ProgressManager.getInstance().computeInNonCancelableSection(() -> {
                            fileBasedIndex.scheduleFileForIndexing(((VirtualFileWithId) fileOrDir).getId(),
                                    fileOrDir,
                                    false);
                            return Unit.INSTANCE;
                        });
                    }
                    return true;
                }, virtualFile -> true);
            }
        }

        Collection<VirtualFile> filesToUpdate =
                fileBasedIndex.getChangedFilesCollector().getAllFilesToUpdate();
        System.out.println("Files to update: " + filesToUpdate.size());
        IndexUpdateRunner.FileSet fileSet =
                new IndexUpdateRunner.FileSet(project, "files to update", filesToUpdate);
        IndexUpdateRunner indexUpdateRunner = new IndexUpdateRunner(fileBasedIndex, 2);
        indexUpdateRunner.indexFiles(project,
                Collections.singletonList(fileSet),
                ProgressManager.getInstance().getProgressIndicator());
    }

}
