package org.jetbrains.kotlin.com.intellij.util.indexing;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.search.FilenameIndex;
import org.jetbrains.kotlin.com.intellij.util.SmartList;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RegisteredIndexes {

    @NotNull
    private final FileDocumentManager myFileDocumentManager;
    @NotNull
    private final CoreFileBasedIndex myFileBasedIndex;
    @NotNull
    private final Future<IndexConfiguration> myStateFuture;

    private final List<ID<?, ?>> myIndicesForDirectories = new SmartList<>();

    private final Set<ID<?, ?>> myNotRequiringContentIndices = new HashSet<>();
    private final Set<ID<?, ?>> myRequiringContentIndices = new HashSet<>();
    private final Set<FileType> myNoLimitCheckTypes = new HashSet<>();

    private volatile boolean myExtensionsRelatedDataWasLoaded;

    private volatile boolean myInitialized;

    private volatile IndexConfiguration myState;
    private volatile Future<?> myAllIndicesInitializedFuture;

    private final Map<ID<?, ?>, DocumentUpdateTask> myUnsavedDataUpdateTasks = new ConcurrentHashMap<>();

    private final AtomicBoolean myShutdownPerformed = new AtomicBoolean(false);

    public RegisteredIndexes(@NotNull FileDocumentManager fileDocumentManager,
                             @NotNull CoreFileBasedIndex fileBasedIndex) {
        myFileDocumentManager = fileDocumentManager;
        myFileBasedIndex = fileBasedIndex;
        myStateFuture = IndexDataInitializer.submitGenesisTask(new CoreFileBasedIndexDataInitialization(fileBasedIndex, this));
    }

    boolean performShutdown() {
        return myShutdownPerformed.compareAndSet(false, true);
    }

    void setState(@NotNull IndexConfiguration state) {
        myState = state;
    }

    public IndexConfiguration getState() {
        return myState;
    }

    IndexConfiguration getConfigurationState() {
        IndexConfiguration state = myState; // memory barrier
        if (state == null) {
            try {
                myState = state = myStateFuture.get();
            }
            catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
        return state;
    }

    void waitUntilAllIndicesAreInitialized() {
        waitUntilIndicesAreInitialized();
        await(myAllIndicesInitializedFuture);
    }

    public void waitUntilIndicesAreInitialized() {
        await(myStateFuture);
    }

    public void extensionsDataWasLoaded() {
        myExtensionsRelatedDataWasLoaded = true;
    }

    void markInitialized() {
        myInitialized = true;
    }

    void ensureLoadedIndexesUpToDate() {
        myAllIndicesInitializedFuture = IndexDataInitializer.submitGenesisTask(() -> {
            if (!myShutdownPerformed.get()) {
//                myFileBasedIndex.ensureStaleIdsDeleted();
                myFileBasedIndex.getChangedFilesCollector().ensureUpToDateAsync();
            }
            return null;
        });
    }

    @SuppressWarnings("removal")
    void registerIndexExtension(@NotNull FileBasedIndexExtension<?, ?> extension) {
        ID<?, ?> name = extension.getName();
        if (extension.dependsOnFileContent()) {
            myUnsavedDataUpdateTasks.put(name, new DocumentUpdateTask(name));
        }

        if (extension.getName() == FilenameIndex.NAME && FileBasedIndexExtension.USE_VFS_FOR_FILENAME_INDEX) {
            return;
        }

        if (!extension.dependsOnFileContent()) {
            if (extension.indexDirectories()) myIndicesForDirectories.add(name);
            myNotRequiringContentIndices.add(name);
        }
        else {
            myRequiringContentIndices.add(name);
        }

        myNoLimitCheckTypes.addAll(extension.getFileTypesWithSizeLimitNotApplicable());
    }

    @NotNull
    Set<FileType> getNoLimitCheckFileTypes() {
        return myNoLimitCheckTypes;
    }

    boolean areIndexesReady() {
        return myStateFuture.isDone() && myAllIndicesInitializedFuture != null && myAllIndicesInitializedFuture.isDone();
    }

    boolean isExtensionsDataLoaded() {
        return myExtensionsRelatedDataWasLoaded;
    }

    public boolean isInitialized() {
        return myInitialized;
    }

    Set<ID<?, ?>> getRequiringContentIndices() {
        return myRequiringContentIndices;
    }

    @NotNull
    Set<ID<?, ?>> getNotRequiringContentIndices() {
        return myNotRequiringContentIndices;
    }

    @NotNull
    List<ID<?, ?>> getIndicesForDirectories() {
        return myIndicesForDirectories;
    }

    public boolean isContentDependentIndex(@NotNull ID<?, ?> indexId) {
        return myRequiringContentIndices.contains(indexId);
    }

    UpdateTask<Document> getUnsavedDataUpdateTask(@NotNull ID<?, ?> indexId) {
        return myUnsavedDataUpdateTasks.get(indexId);
    }

    private final class DocumentUpdateTask extends UpdateTask<Document> {
        private final ID<?, ?> myIndexId;

        DocumentUpdateTask(ID<?, ?> indexId) {
            myIndexId = indexId;
        }

        @Override
        public void doProcess(Document document, Project project) {
//            myFileBasedIndex.indexUnsavedDocument(document, myIndexId, project, myFileDocumentManager.getFile(document));
        }
    }

    private static void await(@NotNull Future<?> future) {
        if (ProgressManager.getInstance().isInNonCancelableSection()) {
            try {
                future.get();
            }
            catch (InterruptedException | ExecutionException e) {
                FileBasedIndexImpl.LOG.error(e);
            }
        }
        else {
            ProgressIndicatorUtils.awaitWithCheckCanceled(future);
        }
    }
}
