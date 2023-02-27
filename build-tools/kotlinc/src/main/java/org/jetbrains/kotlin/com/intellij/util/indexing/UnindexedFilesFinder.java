package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.EmptyRunnable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.impl.CachedFileType;
import org.jetbrains.kotlin.com.intellij.psi.search.FileTypeIndex;
import org.jetbrains.kotlin.com.intellij.util.Function;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.projectFilter.FileAddStatus;
import org.jetbrains.kotlin.com.intellij.util.indexing.projectFilter.ProjectIndexableFilesFilterHolder;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class UnindexedFilesFinder {

    private static final Logger LOG = Logger.getInstance(UnindexedFilesFinder.class);

    private final Project myProject;
    private final CoreFileBasedIndex myFileBasedIndex;
    private final UpdatableIndex<FileType, Void, FileContent, ?> myFileTypeIndex;
    private final Collection<FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor>
            myStateProcessors;
    private final @Nullable Function<? super IndexedFile, Boolean> myForceReindexingTrigger;
    private final @NotNull ProjectIndexableFilesFilterHolder myIndexableFilesFilterHolder;
    private final boolean myShouldProcessUpToDateFiles;

    public UnindexedFilesFinder(@NotNull Project project,
                                @NotNull CoreFileBasedIndex fileBasedIndex,
                                @Nullable Function<? super IndexedFile, Boolean> forceReindexingTrigger) {
        myProject = project;
        myFileBasedIndex = fileBasedIndex;
        myFileTypeIndex = fileBasedIndex.getIndex(FileTypeIndex.NAME);

        myStateProcessors = FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()
                .stream()
                .map(ex -> ex.createFileIndexingStatusProcessor(project))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        myForceReindexingTrigger = forceReindexingTrigger;

        myShouldProcessUpToDateFiles = ContainerUtil.find(myStateProcessors,
                FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor::shouldProcessUpToDateFiles) !=
                                       null;

        myIndexableFilesFilterHolder = fileBasedIndex.getIndexableFilesFilterHolder();
    }

    @Nullable
    public UnindexedFileStatus getFileStatus(@NotNull VirtualFile file) {
        ProgressManager.checkCanceled(); // give a chance to suspend indexing
        if (!file.isValid()) {
            return null;
        }

        Supplier<@NotNull Boolean> checker = CachedFileType.getFileTypeChangeChecker();
        FileType cachedFileType = file.getFileType();
        boolean applyIndexValuesSeparately =
                CoreFileBasedIndex.isWritingIndexValuesSeparatedFromCountingForContentIndependentIndexes();
        return ReadAction.compute(() -> {
            if (myProject.isDisposed() || !file.isValid()) {
                return null;
            }
            FileType fileType = checker.get() ? cachedFileType : null;

            AtomicBoolean indexesWereProvidedByInfrastructureExtension = new AtomicBoolean();
            AtomicLong timeProcessingUpToDateFiles = new AtomicLong();
            AtomicLong timeUpdatingContentLessIndexes = new AtomicLong();
            AtomicLong timeIndexingWithoutContent = new AtomicLong();

            IndexedFileImpl indexedFile = new IndexedFileImpl(file, fileType, myProject);
            int inputId = FileBasedIndex.getFileId(file);
            boolean fileWereJustAdded =
                    myIndexableFilesFilterHolder.addFileId(inputId, myProject) ==
                    FileAddStatus.ADDED;

            AtomicBoolean shouldIndex = new AtomicBoolean();

            Ref<Runnable> finalization = new Ref<>();
            boolean isDirectory = file.isDirectory();
            FileIndexingState fileTypeIndexState = null;
            if (!isDirectory &&
                !CoreFileBasedIndex.isTooLarge(file, file.getLength(), Collections.emptySet())) {
                if ((fileTypeIndexState =
                        myFileTypeIndex.getIndexingStateForFile(inputId, indexedFile)) ==
                    FileIndexingState.OUT_DATED) {
                    if (myFileBasedIndex.doTraceIndexUpdates()) {
                        LOG.info("Scheduling full indexing of " +
                                 indexedFile.getFileName() +
                                 " because file type index is outdated");
                    }
                    myFileBasedIndex.dropNontrivialIndexedStates(inputId);
                    shouldIndex.set(true);
                } else {
                    final List<ID<?, ?>> affectedIndexCandidates =
                            myFileBasedIndex.getAffectedIndexCandidates(indexedFile);
                    for (int i = 0, size = affectedIndexCandidates.size(); i < size; ++i) {
                        final ID<?, ?> indexId = affectedIndexCandidates.get(i);
//                        if (FileBasedIndexScanUtil.isManuallyManaged(indexId)) continue;
                        try {
                            if (myFileBasedIndex.needsFileContentLoading(indexId)) {
                                FileIndexingState fileIndexingState =
                                        myFileBasedIndex.shouldIndexFile(indexedFile, indexId);
                                boolean indexInfrastructureExtensionInvalidated = false;
                                if (fileIndexingState == FileIndexingState.UP_TO_DATE &&
                                    myShouldProcessUpToDateFiles) {
                                    for (FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor p : myStateProcessors) {
                                        long nowTime = System.nanoTime();
                                        try {
                                            if (!p.processUpToDateFile(indexedFile,
                                                    inputId,
                                                    indexId)) {
                                                indexInfrastructureExtensionInvalidated = true;
                                            }
                                        } finally {
                                            timeProcessingUpToDateFiles.addAndGet(System.nanoTime() -
                                                                                  nowTime);
                                        }
                                    }
                                }
                                if (indexInfrastructureExtensionInvalidated) {
                                    fileIndexingState =
                                            myFileBasedIndex.shouldIndexFile(indexedFile, indexId);
                                }
                                if (fileIndexingState.updateRequired()) {
                                    if (myFileBasedIndex.doTraceStubUpdates(indexId)) {
                                        CoreFileBasedIndex.LOG.info("Scheduling indexing of " +
                                                                    indexedFile.getFileName() +
                                                                    " by request of index; " +
                                                                    indexId +
                                                                    (indexInfrastructureExtensionInvalidated ? " because extension invalidated;" : "") +
                                                                    ((myFileBasedIndex.acceptsInput(
                                                                            indexId,
                                                                            indexedFile)) ? " accepted;" : " unaccepted;") +
                                                                    ("indexing state = " +
                                                                     myFileBasedIndex.getIndexingState(
                                                                             indexedFile,
                                                                             indexId)));
                                    }

                                    long nowTime = System.nanoTime();
                                    boolean wasIndexedByInfrastructure;
                                    try {
                                        wasIndexedByInfrastructure =
                                                tryIndexWithoutContentViaInfrastructureExtension(
                                                        indexedFile,
                                                        inputId,
                                                        indexId);
                                    } finally {
                                        timeIndexingWithoutContent.addAndGet(System.nanoTime() -
                                                                             nowTime);
                                    }
                                    if (wasIndexedByInfrastructure) {
                                        indexesWereProvidedByInfrastructureExtension.set(true);
                                    } else {
                                        shouldIndex.set(true);
                                        // NOTE! Do not break the loop here. We must process ALL
                                        // IDs and pass them to the FileIndexingStatusProcessor
                                        // so that it can invalidate all "indexing states" (by
                                        // means of clearing IndexingStamp)
                                        // for all indexes that became invalid. See IDEA-252846
                                        // for more details.
                                    }
                                }
                            }
                        } catch (RuntimeException e) {
                            final Throwable cause = e.getCause();
                            if (cause instanceof IOException || cause instanceof StorageException) {
                                LOG.info(e);
                                myFileBasedIndex.requestRebuild(indexId);
                            } else {
                                throw e;
                            }
                        }
                    }
                }
            }

            boolean mayMarkFileIndexed = true;
            long nowTime = System.nanoTime();
            List<SingleIndexValueApplier<?>> appliers =
                    applyIndexValuesSeparately ? new SmartList<>() : Collections.emptyList();

            try {
                for (ID<?, ?> indexId : myFileBasedIndex.getContentLessIndexes(isDirectory)) {
                    if (!RebuildStatus.isOk(indexId)) {
                        mayMarkFileIndexed = false;
                        continue;
                    }
                    if (FileTypeIndex.NAME.equals(indexId) &&
                        fileTypeIndexState != null &&
                        !fileTypeIndexState.updateRequired()) {
                        continue;
                    }
                    if (myFileBasedIndex.shouldIndexFile(indexedFile, indexId).updateRequired()) {
                        SingleIndexValueApplier<?> applier =
                                myFileBasedIndex.createSingleIndexValueApplier(indexId,
                                        file,
                                        inputId,
                                        new IndexedFileWrapper(indexedFile),
                                        applyIndexValuesSeparately);
                        if (applier == null) {
                            continue;
                        }

                        if (applyIndexValuesSeparately) {
                            appliers.add(applier);
                        } else {
                            applier.apply();
                        }
                    }
                }
            } finally {
                timeUpdatingContentLessIndexes.addAndGet(System.nanoTime() - nowTime);
            }

            if (appliers.isEmpty()) {
                finishGettingStatus(file, indexedFile, inputId, shouldIndex, mayMarkFileIndexed);
                finalization.set(EmptyRunnable.getInstance());
            } else {
                boolean finalMayMarkFileIndexed = mayMarkFileIndexed;
                finalization.set(() -> {
                    long applyingStart = System.nanoTime();
                    try {
                        for (SingleIndexValueApplier<?> applier : appliers) {
                            applier.apply();
                        }
                    } finally {
                        timeUpdatingContentLessIndexes.addAndGet(System.nanoTime() - applyingStart);
                    }
                    finishGettingStatus(file,
                            indexedFile,
                            inputId,
                            shouldIndex,
                            finalMayMarkFileIndexed);
                });
            }

            finalization.get().run();
            return new UnindexedFileStatus(shouldIndex.get(),
                    indexesWereProvidedByInfrastructureExtension.get(),
                    timeProcessingUpToDateFiles.get(),
                    timeUpdatingContentLessIndexes.get(),
                    timeIndexingWithoutContent.get());
        });
    }

    private void finishGettingStatus(@NotNull VirtualFile file,
                                     IndexedFileImpl indexedFile,
                                     int inputId,
                                     AtomicBoolean shouldIndex,
                                     boolean mayMarkFileIndexed) {
        if (myForceReindexingTrigger != null && myForceReindexingTrigger.fun(indexedFile)) {
            myFileBasedIndex.dropNontrivialIndexedStates(inputId);
            shouldIndex.set(true);
        }

        IndexingStamp.flushCache(inputId);
        if (!shouldIndex.get() && mayMarkFileIndexed) {
            IndexingFlag.setFileIndexed(file);
        }
    }

    private boolean tryIndexWithoutContentViaInfrastructureExtension(IndexedFile fileContent,
                                                                     int inputId,
                                                                     ID<?, ?> indexId) {
        for (FileBasedIndexInfrastructureExtension.FileIndexingStatusProcessor processor :
                myStateProcessors) {
            if (processor.tryIndexFileWithoutContent(fileContent, inputId, indexId)) {
                CoreFileBasedIndex.setIndexedState(myFileBasedIndex.getIndex(indexId),
                        fileContent,
                        inputId,
                        true);
                if (myFileBasedIndex.doTraceStubUpdates(indexId)) {
                    LOG.info("File " +
                             fileContent.getFileName() +
                             " indexed using extension for " +
                             indexId +
                             " without content");
                }
                return true;
            }
        }
        return false;
    }
}
