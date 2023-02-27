package org.jetbrains.kotlin.com.intellij.util.indexing.events;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.util.ShutDownTracker;
import org.jetbrains.kotlin.com.intellij.openapi.util.registry.Registry;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.AsyncFileListener;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VfsUtilCore;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileVisitor;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.kotlin.com.intellij.util.concurrency.BoundedTaskExecutor;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentIntObjectHashMap;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.indexing.AlreadyDisposedException;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreFileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexProjectHandler;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexUpToDateCheckIn;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingFlag;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexingStamp;
import org.jetbrains.kotlin.com.intellij.util.indexing.RegisteredIndexes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Phaser;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import kotlin.Unit;

public class ChangedFilesCollector extends IndexedFilesListener {

    private static final Logger LOG = Logger.getInstance(ChangedFilesCollector.class);
    public static final boolean CLEAR_NON_INDEXABLE_FILE_DATA =
            SystemProperties.getBooleanProperty("idea.indexes.clear.non.indexable.file.data", true);

    @SuppressWarnings("deprecation")
    private final ConcurrentIntObjectHashMap<VirtualFile> myFilesToUpdate =
            new ConcurrentIntObjectHashMap<>();
    private final AtomicInteger myProcessedEventIndex = new AtomicInteger();
    private final Phaser myWorkersFinishedSync = new Phaser() {
        @Override
        protected boolean onAdvance(int phase, int registeredParties) {
            return false;
        }
    };

    private final Executor myVfsEventsExecutor =
            AppExecutorUtil.createBoundedApplicationPoolExecutor(
                    "FileBasedIndex Vfs Event Processor",
                    1);
    private final AtomicInteger myScheduledVfsEventsWorkers = new AtomicInteger();
    private final CoreFileBasedIndex myFileBasedIndex =
            (CoreFileBasedIndex) FileBasedIndex.getInstance();

    @Override
    protected void iterateIndexableFiles(@NotNull VirtualFile file,
                                         @NotNull ContentIterator iterator) {
        if (myFileBasedIndex.belongsToIndexableFiles(file)) {
            VfsUtilCore.visitChildrenRecursively(file, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file11) {
                    if (!myFileBasedIndex.belongsToIndexableFiles(file11)) {
                        return false;
                    }
                    iterator.processFile(file11);
                    return true;
                }
            });
        }
    }

    public void scheduleForUpdate(@NotNull VirtualFile file) {
        int fileId = FileBasedIndex.getFileId(file);
        if (!(file instanceof DeletedVirtualFileStub)) {
            Set<Project> projects = myFileBasedIndex.getContainingProjects(file);
            if (projects.isEmpty()) {
                removeNonIndexableFileData(file, fileId);
                return;
            }
        }

        VfsEventsMerger.tryLog("ADD_TO_UPDATE", file);
        myFilesToUpdate.put(fileId, file);
    }

    public void removeScheduledFileFromUpdate(VirtualFile file) {
        int fileId = FileBasedIndex.getFileId(file);
        VirtualFile alreadyScheduledFile = myFilesToUpdate.get(fileId);
        if (!(alreadyScheduledFile instanceof DeletedVirtualFileStub)) {
            VfsEventsMerger.tryLog("PULL_OUT_FROM_UPDATE", file);
            myFilesToUpdate.remove(fileId);
        }
    }

    public void removeFileIdFromFilesScheduledForUpdate(int fileId) {
        myFilesToUpdate.remove(fileId);
    }

    public boolean containsFileId(int fileId) {
        return myFilesToUpdate.containsKey(fileId);
    }

    public Iterator<@NotNull VirtualFile> getFilesToUpdate() {
        return myFilesToUpdate.values().iterator();
    }

    public Collection<VirtualFile> getAllFilesToUpdate() {
        ensureUpToDate();
        return myFilesToUpdate.isEmpty() ? Collections.emptyList() :
                Collections.unmodifiableCollection(
                myFilesToUpdate.values());
    }

    // it's important here to don't load any extension here, so we don't check scopes.
    public Collection<VirtualFile> getAllPossibleFilesToUpdate() {

        ReadAction.compute(() -> {
            processFilesInReadAction(info -> {
                myFilesToUpdate.put(info.getFileId(),
                        info.isFileRemoved() ?
                                new DeletedVirtualFileStub(((VirtualFileWithId) info.getFile()))
                                : info.getFile());
                return true;

            });
            return Unit.INSTANCE;
        });

        return new ArrayList<>(myFilesToUpdate.values());
    }

    public void clearFilesToUpdate() {
        myFilesToUpdate.clear();
    }

    @Override
    @NotNull
    public AsyncFileListener.ChangeApplier prepareChange(@NotNull List<?
            extends @NotNull VFileEvent> events) {
        boolean shouldCleanup =
                ContainerUtil.exists(events, ChangedFilesCollector::memoryStorageCleaningNeeded);
        ChangeApplier superApplier = super.prepareChange(events);

        return new ChangeApplier() {
            @Override
            public void beforeVfsChange() {
                if (shouldCleanup) {
                    myFileBasedIndex.cleanupMemoryStorage(false);
                }
                superApplier.beforeVfsChange();
            }

            @Override
            public void afterVfsChange() {
                superApplier.afterVfsChange();
                RegisteredIndexes registeredIndexes = myFileBasedIndex.getRegisteredIndexes();
                if (registeredIndexes != null && registeredIndexes.isInitialized()) {
                    ensureUpToDateAsync();
                }
            }
        };
    }

    private void removeNonIndexableFileData(@NotNull VirtualFile file, int fileId) {
        if (CLEAR_NON_INDEXABLE_FILE_DATA) {
            List<ID<?, ?>> extensions = getIndexedContentDependentExtensions(fileId);
            if (!extensions.isEmpty()) {
                myFileBasedIndex.removeDataFromIndicesForFile(fileId, file, "non_indexable_file");
            }
            IndexingFlag.cleanProcessingFlag(file);
        } else if (ApplicationManager.getApplication().isInternal() &&
                   !ApplicationManager.getApplication().isUnitTestMode()) {
            checkNotIndexedByContentBasedIndexes(file, fileId);
        }
    }

    private static boolean memoryStorageCleaningNeeded(@NotNull VFileEvent event) {
        Object requestor = event.getRequestor();
        return requestor instanceof FileDocumentManager || requestor instanceof PsiManager;
//               ||
//               requestor == LocalHistory.VFS_EVENT_REQUESTOR;
    }

    public boolean isScheduledForUpdate(VirtualFile file) {
        return myFilesToUpdate.containsKey(FileBasedIndex.getFileId(file));
    }

    public void ensureUpToDate() {
        if (!IndexUpToDateCheckIn.isUpToDateCheckEnabled()) {
            return;
        }
        assert ApplicationManager.getApplication().isReadAccessAllowed() ||
               ShutDownTracker.isShutdownHookRunning();
        myFileBasedIndex.waitUntilIndicesAreInitialized();

        if (ApplicationManager.getApplication().isReadAccessAllowed()) {
            processFilesToUpdateInReadAction();
        } else {
            processFilesInReadActionWithYieldingToWriteAction();
        }
    }

    public void ensureUpToDateAsync() {
        if (getEventMerger().getApproximateChangesCount() >= 20 &&
            myScheduledVfsEventsWorkers.compareAndSet(0, 1)) {
            myVfsEventsExecutor.execute(() -> {
                try {
                    processFilesInReadActionWithYieldingToWriteAction();
                    LOG.trace("ensureUpToDateAsync");

                    if (Registry.is("try.starting.dumb.mode.where.many.files.changed")) {
                        Project project = ProjectCoreUtil.theOnlyOpenProject();
                        if (project != null) {
                            try {
                                FileBasedIndexProjectHandler.scheduleReindexingInDumbMode(project);
                            } catch (AlreadyDisposedException | ProcessCanceledException ignored) {
                            } catch (Exception e) {
                                LOG.error(e);
                            }
//                        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
//                            try {
//                                FileBasedIndexProjectHandler.scheduleReindexingInDumbMode
//                                (project);
//                            }
//                            }
//                        }
                        }
                    }
                } finally {
                    myScheduledVfsEventsWorkers.decrementAndGet();
                }
            });
        }
    }

    public void processFilesToUpdateInReadAction() {
        processFilesInReadAction(new VfsEventsMerger.VfsEventProcessor() {
            private final CoreStubIndex.FileUpdateProcessor perFileElementTypeUpdateProcessor =
                    ((CoreStubIndex) StubIndex.getInstance()).getPerFileElementTypeModificationTrackerUpdateProcessor();

            @Override
            public boolean process(VfsEventsMerger.@NotNull ChangeInfo info) {
                int fileId = info.getFileId();
                VirtualFile file = info.getFile();
                if (info.isTransientStateChanged()) {
                    myFileBasedIndex.doTransientStateChangeForFile(fileId, file);
                }
                if (info.isContentChanged()) {
                    myFileBasedIndex.scheduleFileForIndexing(fileId, file, true);
                }
                if (info.isFileRemoved()) {
                    myFileBasedIndex.doInvalidateIndicesForFile(fileId, file);
                }
                if (info.isFileAdded()) {
                    myFileBasedIndex.scheduleFileForIndexing(fileId, file, false);
                }
                if (CoreStubIndex.PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE ==
                    CoreStubIndex.PerFileElementTypeStubChangeTrackingSource.ChangedFilesCollector) {
                    perFileElementTypeUpdateProcessor.processUpdate(file);
                }
                return true;
            }

            @Override
            public void endBatch() {
                if (CoreStubIndex.PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE ==
                    CoreStubIndex.PerFileElementTypeStubChangeTrackingSource.ChangedFilesCollector) {
                    perFileElementTypeUpdateProcessor.endUpdatesBatch();
                }
            }
        });
    }

    private void processFilesInReadAction(@NotNull VfsEventsMerger.VfsEventProcessor processor) {
        ApplicationManager.getApplication().assertReadAccessAllowed();

        int publishedEventIndex = getEventMerger().getPublishedEventIndex();
        int processedEventIndex = myProcessedEventIndex.get();
        if (processedEventIndex == publishedEventIndex) {
            return;
        }

        myWorkersFinishedSync.register();
        int phase = myWorkersFinishedSync.getPhase();
        try {
            myFileBasedIndex.waitUntilIndicesAreInitialized();
            getEventMerger().processChanges(new VfsEventsMerger.VfsEventProcessor() {
                @Override
                public boolean process(VfsEventsMerger.@NotNull ChangeInfo changeInfo) {
                    return ConcurrencyUtil.withLock(myFileBasedIndex.myWriteLock, () -> {
                        try {
                            ProgressManager.getInstance().executeNonCancelableSection(() -> {
                                processor.process(changeInfo);
                            });
                        } finally {
                            IndexingStamp.flushCache(changeInfo.getFileId());
                        }
                        return true;
                    });
                }

                @Override
                public void endBatch() {
                    ConcurrencyUtil.withLock(myFileBasedIndex.myWriteLock, processor::endBatch);
                }
            });
        } finally {
            myWorkersFinishedSync.arriveAndDeregister();
        }

        try {
            myWorkersFinishedSync.awaitAdvance(phase);
        } catch (RejectedExecutionException e) {
            LOG.warn(e);
            throw new ProcessCanceledException(e);
        }

        if (getEventMerger().getPublishedEventIndex() == publishedEventIndex) {
            myProcessedEventIndex.compareAndSet(processedEventIndex, publishedEventIndex);
        }
    }

    private void processFilesInReadActionWithYieldingToWriteAction() {
        while (getEventMerger().hasChanges()) {
            ReadAction.compute(() -> {
                processFilesToUpdateInReadAction();
                return Unit.INSTANCE;
            });
        }
    }

    private void checkNotIndexedByContentBasedIndexes(@NotNull VirtualFile file, int fileId) {
        List<ID<?, ?>> contentDependentIndexes = getIndexedContentDependentExtensions(fileId);
        if (!contentDependentIndexes.isEmpty()) {
            LOG.error("indexes " +
                      contentDependentIndexes +
                      " will not be updated for file = " +
                      file +
                      ", id = " +
                      fileId);
        }
    }

    private @NotNull List<ID<?, ?>> getIndexedContentDependentExtensions(int fileId) {
        List<ID<?, ?>> indexedStates = IndexingStamp.getNontrivialFileIndexedStates(fileId);
        RegisteredIndexes registeredIndexes = myFileBasedIndex.getRegisteredIndexes();
        List<ID<?, ?>> contentDependentIndexes;
        if (registeredIndexes == null) {
            Set<? extends ID<?, ?>> allContentDependentIndexes =
                    FileBasedIndexExtension.EXTENSION_POINT_NAME.getExtensionList()
                            .stream()
                            .filter(FileBasedIndexExtension::dependsOnFileContent)
                            .map(FileBasedIndexExtension::getName)
                            .collect(Collectors.toSet());
            contentDependentIndexes = ContainerUtil.filter(indexedStates,
                    id -> !allContentDependentIndexes.contains(id));
        } else {
            contentDependentIndexes =
                    ContainerUtil.filter(indexedStates, registeredIndexes::isContentDependentIndex);
        }
        return contentDependentIndexes;
    }

    @TestOnly
    public void waitForVfsEventsExecuted(long timeout, @NotNull TimeUnit unit) throws Exception {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ((BoundedTaskExecutor) myVfsEventsExecutor).waitAllTasksExecuted(timeout, unit);
            return;
        }
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            try {
                ((BoundedTaskExecutor) myVfsEventsExecutor).waitAllTasksExecuted(100,
                        TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
//                UIUtil.dispatchAllInvocationEvents();
            }
        }
    }

}
