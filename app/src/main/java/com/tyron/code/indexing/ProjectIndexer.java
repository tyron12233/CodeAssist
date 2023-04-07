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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

public class ProjectIndexer {

    public static void index(Project project, CoreFileBasedIndex fileBasedIndex) {
        fileBasedIndex.registerProjectFileSets(project);
        fileBasedIndex.getIndexableFilesFilterHolder().getProjectIndexableFiles(project);

        ExecutorService executorService = Executors.newSingleThreadExecutor();

        findFilesToIndex(project, fileBasedIndex);

        Collection<VirtualFile> filesToUpdate = fileBasedIndex
                .getChangedFilesCollector()
                .getAllFilesToUpdate();

        IndexUpdateRunner.FileSet fileSet = new IndexUpdateRunner.FileSet(
                project,
                "files to update",
                filesToUpdate
        );
        IndexUpdateRunner indexUpdateRunner = new IndexUpdateRunner(
                fileBasedIndex,
                2
        );
        try {
            indexUpdateRunner.indexFiles(project,
                    Collections.singletonList(fileSet),
                    ProgressManager.getInstance().getProgressIndicator());
        } catch (IndexUpdateRunner.IndexingInterruptedException e) {
            // ignored for now
        }
    }

    private static void findFilesToIndex(Project project, CoreFileBasedIndex fileBasedIndex) {
        UnindexedFilesFinder finder =
                new UnindexedFilesFinder(project, fileBasedIndex, indexedFile -> false);

        for (IndexableFilesIterator indexingIterator : fileBasedIndex.getIndexableFilesProviders(
                project)) {
            indexingIterator.iterateFiles(project, fileOrDir -> {

                UnindexedFileStatus fileStatus = finder.getFileStatus(fileOrDir);
                if (fileStatus != null && fileStatus.isShouldIndex()) {
                    System.out.println("Indexing " + fileOrDir.getName() + " because " + fileStatus.isShouldIndex());
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
    }

}
