package org.jetbrains.kotlin.com.intellij.util.indexing;

import static org.jetbrains.kotlin.com.intellij.util.indexing.CoreStubIndex.deleteWithRenamingIfExists;
import static org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.TransientFileContentIndex.createIndex;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.google.common.collect.Iterators;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager;
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.NoAccessDuringPsiEvents;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.AsyncEventSupport;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.NewVirtualFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentTransactionListener;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiFileImpl;
import org.jetbrains.kotlin.com.intellij.psi.search.DelegatingGlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.FileTypeIndex;
import org.jetbrains.kotlin.com.intellij.psi.search.FilenameIndex;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.impl.VirtualFileEnumeration;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatingIndex;
import org.jetbrains.kotlin.com.intellij.util.ObjectUtils;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.SmartFMap;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.SmartHashSet;
import org.jetbrains.kotlin.com.intellij.util.indexing.contentQueue.CachedFileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.events.ChangedFilesCollector;
import org.jetbrains.kotlin.com.intellij.util.indexing.events.DeletedVirtualFileStub;
import org.jetbrains.kotlin.com.intellij.util.indexing.events.VfsEventsMerger;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapReduceIndexMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.TransientFileContentIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.VfsAwareMapReduceIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.projectFilter.FileAddStatus;
import org.jetbrains.kotlin.com.intellij.util.indexing.projectFilter.IncrementalProjectIndexableFilesFilterHolder;
import org.jetbrains.kotlin.com.intellij.util.indexing.projectFilter.ProjectIndexableFilesFilterHolder;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesContributor;
import org.jetbrains.kotlin.com.intellij.util.indexing.snapshot.SnapshotInputMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;
import org.jetbrains.kotlin.com.intellij.util.io.CorruptedException;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;
import org.jetbrains.kotlin.com.intellij.util.messages.MessageBus;
import org.jetbrains.kotlin.com.intellij.util.messages.SimpleMessageBusConnection;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CoreFileBasedIndex extends FileBasedIndexEx {

    private static final ThreadLocal<VirtualFile> ourIndexedFile = new ThreadLocal<>();
    private static final ThreadLocal<IndexWritingFile> ourWritingIndexFile = new ThreadLocal<>();
    private static final ThreadLocal<VirtualFile> ourFileToBeIndexed = new ThreadLocal<>();

    public static final Logger LOG = Logger.getInstance(CoreFileBasedIndex.class);
    private static boolean ourWritingIndexValuesSeparatedFromCountingForContentIndependentIndexes;
    private static boolean ourWritingIndexValuesSeparatedFromCounting;
    public boolean myIsUnitTestMode;

    private RegisteredIndexes myRegisteredIndexes;
    private FileDocumentManagerImpl myFileDocumentManager;

    private final PerIndexDocumentVersionMap myLastIndexedDocStamps = new PerIndexDocumentVersionMap();

    private final Set<ID<?, ?>> myUpToDateIndicesForUnsavedOrTransactedDocuments = Collections.synchronizedSet(new HashSet<>());
    private volatile SmartFMap<Document, PsiFile> myTransactionMap = SmartFMap.emptyMap();

    private final AtomicInteger myLocalModCount = new AtomicInteger();
    private final IntSet myStaleIds = new IntOpenHashSet();

    private final NotNullLazyValue<ChangedFilesCollector> myChangedFilesCollector =
            NotNullLazyValue.createValue(() -> Objects.requireNonNull(AsyncEventSupport.EP_NAME.findExtension(
                    ChangedFilesCollector.class)));

    private final List<Pair<IndexableFileSet, Project>> myIndexableSets =
            ContainerUtil.createLockFreeCopyOnWriteList();

    final Lock myReadLock;
    public final Lock myWriteLock;

    private String myShutdownReason;

    private final StorageBufferingHandler myStorageBufferingHandler =
            new StorageBufferingHandler() {
                @NotNull
                @Override
                protected Stream<UpdatableIndex<?, ?, ?, ?>> getIndexes() {
                    IndexConfiguration state = getState();
                    return state.getIndexIDs().stream().map(id -> getIndex(id));
                }
            };
    private ProjectIndexableFilesFilterHolder myIndexableFilesFilterHolder;

    public CoreFileBasedIndex() {
        ReadWriteLock lock = new ReentrantReadWriteLock();
        myReadLock = lock.readLock();
        myWriteLock = lock.writeLock();

        myFileDocumentManager = (FileDocumentManagerImpl) FileDocumentManager.getInstance();
        myIndexableFilesFilterHolder = new IncrementalProjectIndexableFilesFilterHolder();

        MessageBus messageBus = ApplicationManager.getApplication().getMessageBus();
        SimpleMessageBusConnection connection = messageBus.simpleConnect();
        connection.subscribe(PsiDocumentTransactionListener.TOPIC, new PsiDocumentTransactionListener() {
            @Override
            public void transactionStarted(@NotNull Document document, @NotNull PsiFile psiFile) {
                myTransactionMap = myTransactionMap.plus(document, psiFile);
                clearUpToDateIndexesForUnsavedOrTransactedDocs();
            }

            @Override
            public void transactionCompleted(@NotNull Document document, @NotNull PsiFile file) {
                myTransactionMap = myTransactionMap.minus(document);
            }
        });
    }



    private IndexConfiguration getState() {
        return myRegisteredIndexes.getConfigurationState();
    }

    public static boolean isMock(VirtualFile file) {
        return false;
    }

    public void registerIndexableSet(@NotNull IndexableFileSet set, @NotNull Project project) {
        myIndexableSets.add(Pair.create(set, project));
    }

    @Nullable
    @Override
    public VirtualFile getFileBeingCurrentlyIndexed() {
        return null;
    }

    @Nullable
    @Override
    public IndexWritingFile getFileWritingCurrentlyIndexes() {
        return null;
    }

    @SuppressWarnings("removal")
    @Override
    public VirtualFile findFileById(Project project, int id) {
        return null;
    }

    @Override
    public void requestRebuild(@NonNull ID<?, ?> indexId, @NonNull Throwable throwable) {

    }

    @Override
    public void requestReindex(@NonNull VirtualFile file) {

    }

    @NonNull
    @Override
    public IntPredicate getAccessibleFileIdFilter(@Nullable Project project) {
        boolean dumb = project == null || DumbService.isDumb(project);
        if (!dumb) return f -> true;
        DumbModeAccessType dumbModeAccessType = getCurrentDumbModeAccessType();
        if (dumbModeAccessType == null) {
            //throw new IllegalStateException("index access is not allowed in dumb mode");
            return __ -> true;
        }
        if (dumbModeAccessType == DumbModeAccessType.RAW_INDEX_DATA_ACCEPTABLE) return f -> true;
        assert dumbModeAccessType == DumbModeAccessType.RELIABLE_DATA_ONLY;
        return fileId -> !getChangedFilesCollector().containsFileId(fileId);
    }

    @Nullable
    @Override
    public IdFilter extractIdFilter(@Nullable GlobalSearchScope scope, @Nullable Project project) {
        if (scope == null) return projectIndexableFiles(project);
        IdFilter filter = extractFileEnumeration(scope);
        if (filter != null) return filter;
        return projectIndexableFiles(ObjectUtils.chooseNotNull(project, scope.getProject()));
    }

    @Nullable
    private IdFilter extractFileEnumeration(@NotNull GlobalSearchScope scope) {
        VirtualFileEnumeration hint = VirtualFileEnumeration.extract(scope);
        if (hint != null) {
            return new IdFilter() {
                @Override
                public boolean containsFileId(int id) {
                    return hint.contains(id);
                }

                @Override
                public String toString() {
                    return "IdFilter of " + scope;
                }
            };
        }
        Project project = scope.getProject();
        if (project == null) return null;
        // todo support project only content scope
        return projectIndexableFiles(project);
    }

    @Nullable
    @Override
    public IdFilter projectIndexableFiles(@Nullable Project project) {
        return null;
    }

    @SuppressWarnings("ConstantConditions")
    @NonNull
    @Override
    public <K, V> UpdatableIndex<K, V, FileContent, ?> getIndex(ID<K, V> indexId) {
        return myRegisteredIndexes.getState().getIndex(indexId);
    }

    @Override
    public void waitUntilIndicesAreInitialized() {
        myRegisteredIndexes.waitUntilIndicesAreInitialized();
    }

    @Override
    public void loadIndexes() {
        if (myRegisteredIndexes == null) {
            super.loadIndexes();

            LOG.assertTrue(myRegisteredIndexes == null);

            myStorageBufferingHandler.resetState();
            myRegisteredIndexes = new RegisteredIndexes(myFileDocumentManager, this);
            myShutdownReason = null;
        }
    }

    @Override
    public <K> boolean ensureUpToDate(@NonNull ID<K, ?> indexId,
                                      @Nullable Project project,
                                      @Nullable GlobalSearchScope filter,
                                      @Nullable VirtualFile restrictedFile) {;
        String shutdownReason = myShutdownReason;
        if (shutdownReason != null) {
            LOG.info("FileBasedIndex is currently shutdown because: " + shutdownReason);
            return false;
        }
//        if (FORBID_LOOKUP_IN_NON_CANCELLABLE_SECTIONS && ProgressManager.getInstance().isInNonCancelableSection()) {
//            LOG.error("Indexes should not be accessed in non-cancellable section");
//        }

        ProgressManager.checkCanceled();
//        SlowOperations.assertSlowOperationsAreAllowed();
        getChangedFilesCollector().ensureUpToDate();
        ApplicationManager.getApplication().assertReadAccessAllowed();
//        NoAccessDuringPsiEvents.checkCallContext(indexId);

        if (!needsFileContentLoading(indexId)) {
            return true; //indexed eagerly in foreground while building unindexed file list
        }

        if (filter == GlobalSearchScope.EMPTY_SCOPE) {
//            filter instanceof DelegatingGlobalSearchScope && ((DelegatingGlobalSearchScope)filter).() == GlobalSearchScope.EMPTY_SCOPE) {
            return false;
        }

        if (project == null) {
            LOG.warn("Please provide a GlobalSearchScope with specified project. Otherwise it might lead to performance problems!",
                    new Exception());
        }

        if (project != null && project.isDefault()) {
            LOG.error("FileBasedIndex should not receive default project");
        }

        if (myReentrancyGuard.get()) {
            //assert false : "ensureUpToDate() is not reentrant!";
            return true;
        }
        myReentrancyGuard.set(Boolean.TRUE);
        try {
            if (!RebuildStatus.isOk(indexId)) {
                try {
                    if (!RebuildStatus.isOk(indexId)) {
                        if (getCurrentDumbModeAccessType_NoDumbChecks() == null) {
                            throw new RuntimeException("index " + indexId + " has status " + RebuildStatus.getStatus(indexId));
                        }
                        return false;
                    }
                    if (!DumbService.isDumb(project) || getCurrentDumbModeAccessType_NoDumbChecks() == null) {
                        forceUpdate(project, filter, restrictedFile);
                    }
                    indexUnsavedDocuments(indexId, project, filter, restrictedFile);
                }
                catch (RuntimeException e) {
                    final Throwable cause = e.getCause();
                    if (cause instanceof StorageException || cause instanceof IOException) {
                        scheduleRebuild(indexId, e);
                    }
                    else {
                        throw e;
                    }
                }
            }
        }  finally {
            myReentrancyGuard.set(Boolean.FALSE);
        }

        return true;
    }

    public void flush() throws StorageException {
        for (ID<?, ?> indexID : getState().getIndexIDs()) {
            getIndex(indexID).flush();
        }
    }

    private class VirtualFileUpdateTask extends UpdateTask<VirtualFile> {
        @Override
        public void doProcess(VirtualFile item, Project project) {
            processRefreshedFile(project, new CachedFileContent(item));
        }
    }

    // caller is responsible to ensure no concurrent same document processing
    private void processRefreshedFile(@Nullable Project project, @NotNull final CachedFileContent fileContent) {
        // ProcessCanceledException will cause re-adding the file to processing list
        final VirtualFile file = fileContent.getVirtualFile();
        if (getChangedFilesCollector().isScheduledForUpdate(file)) {
            try {
                indexFileContent(project, fileContent, null).apply(file);
            }
            finally {
                IndexingStamp.flushCache(getFileId(file));
                IndexingFlag.unlockFile(file);
            }
        }
    }

    private final VirtualFileUpdateTask myForceUpdateTask = new VirtualFileUpdateTask();

    private void forceUpdate(@Nullable Project project, @Nullable final GlobalSearchScope filter, @Nullable final VirtualFile restrictedTo) {
        Collection<VirtualFile> allFilesToUpdate = getChangedFilesCollector().getAllFilesToUpdate();
        if (!allFilesToUpdate.isEmpty()) {
            boolean includeFilesFromOtherProjects = restrictedTo == null && project == null;
            List<VirtualFile> virtualFilesToBeUpdatedForProject = ContainerUtil.filter(
                    allFilesToUpdate,
                    new ProjectFilesCondition(projectIndexableFiles(project), filter, restrictedTo, includeFilesFromOtherProjects)
            );

            if (!virtualFilesToBeUpdatedForProject.isEmpty()) {
                myForceUpdateTask.processAll(virtualFilesToBeUpdatedForProject, project);
            }
        }
    }

        @Nullable
    @Override
    public VirtualFile findFileById(int id) {
        String pathById = FileIdStorage.findPathById(id);
        if (pathById == null) {
            return null;
        }
        if (pathById.contains("!/")) {
            return StandardFileSystems.jar().findFileByPath(pathById);
        }
        return StandardFileSystems.local().findFileByPath(pathById);
    }

    @Override
    public void registerProjectFileSets(@NonNull Project project) {
        if (IndexableFilesIndex.isEnabled()) {
            IndexableFilesIndex instance = IndexableFilesIndex.getInstance(project);
            registerIndexableSet(new IndexableFileSet() {
                @Override
                public boolean isInSet(@NotNull VirtualFile file) {
                    return instance.shouldBeIndexed(file);
                }
            }, project);
            return;
        }
        for (IndexableFilesContributor extension :
                IndexableFilesContributor.EP_NAME.getExtensions()) {
            Predicate<VirtualFile> contributorsPredicate = extension.getOwnFilePredicate(project);
            registerIndexableSet(new IndexableFileSet() {
                @Override
                public boolean isInSet(@NotNull VirtualFile file) {
                    return contributorsPredicate.test(file);
                }

                @Override
                public String toString() {
                    return "IndexableFileSet[" + extension + "]";
                }
            }, project);
        }
    }

    @NonNull
    @Override
    public Logger getLogger() {
        return LOG;
    }

    void clearIndicesIfNecessary() {
        waitUntilIndicesAreInitialized();
        for (ID<?, ?> indexId : getState().getIndexIDs()) {
            try {
                RebuildStatus.clearIndexIfNecessary(indexId, () -> clearIndex(indexId));
            }
            catch (StorageException e) {
                LOG.error(e);
                requestRebuild(indexId);
            }
        }
    }

    void clearIndex(@NotNull ID<?, ?> indexId) throws StorageException {
        if (IOUtil.isSharedCachesEnabled()) {
            IOUtil.OVERRIDE_BYTE_BUFFERS_USE_NATIVE_BYTE_ORDER_PROP.set(false);
        }
        try {
            advanceIndexVersion(indexId);
            getIndex(indexId).clear();
        }
        finally {
            IOUtil.OVERRIDE_BYTE_BUFFERS_USE_NATIVE_BYTE_ORDER_PROP.remove();
        }
    }

    @NotNull
    private Set<Document> getTransactedDocuments() {
        return myTransactionMap.keySet();
    }

    private void indexUnsavedDocuments(@NotNull final ID<?, ?> indexId,
                                       @Nullable Project project,
                                       @Nullable GlobalSearchScope filter,
                                       @Nullable VirtualFile restrictedFile) {
        if (myUpToDateIndicesForUnsavedOrTransactedDocuments.contains(indexId)) {
            return; // no need to index unsaved docs        // todo: check scope ?
        }

        Document[] unsavedDocuments = myFileDocumentManager.getUnsavedDocuments();
        Set<Document> transactedDocuments = getTransactedDocuments();
        Document[] uncommittedDocuments = project == null ? Document.EMPTY_ARRAY :
                PsiDocumentManager.getInstance(project).getUncommittedDocuments();

        if (unsavedDocuments.length == 0 && uncommittedDocuments.length == 0 && transactedDocuments.isEmpty()) return;

        final Set<Document> documents = new HashSet<>();
        Collections.addAll(documents, unsavedDocuments);
        documents.addAll(transactedDocuments);
        Collections.addAll(documents, uncommittedDocuments);

        Collection<Document> documentsToProcessForProject = ContainerUtil.filter(documents,
                document -> belongsToScope(
                        myFileDocumentManager.getFile(document), restrictedFile,
                        filter));
        if (!documentsToProcessForProject.isEmpty()) {
            UpdateTask<Document> task = myRegisteredIndexes.getUnsavedDataUpdateTask(indexId);
            assert task != null : "Task for unsaved data indexing was not initialized for index " + indexId;

            if (myStorageBufferingHandler.runUpdate(true, () -> task.processAll(documentsToProcessForProject, project)) &&
                documentsToProcessForProject.size() == documents.size() &&
                !hasActiveTransactions()
            ) {
                myUpToDateIndicesForUnsavedOrTransactedDocuments.add(indexId);
            }
        }
    }

    private boolean hasActiveTransactions() {
        return !myTransactionMap.isEmpty();
    }

    private void advanceIndexVersion(ID<?, ?> indexId) {
        try {
            IndexVersion.rewriteVersion(indexId, myRegisteredIndexes.getState().getIndexVersion(indexId));
        }
        catch (IOException e) {
            LOG.error(e);
        }
    }

    public ChangedFilesCollector getChangedFilesCollector() {
        return myChangedFilesCollector.getValue();
    }

    public boolean belongsToProjectIndexableFiles(@NotNull VirtualFile file, @NotNull Project project) {
        return ContainerUtil.find(myIndexableSets, pair -> pair.second.equals(project) && pair.first.isInSet(file)) != null;
    }

    public boolean belongsToIndexableFiles(@NotNull VirtualFile file) {
        return ContainerUtil.find(myIndexableSets, pair -> pair.first.isInSet(file)) != null;
    }

    @Override
    public void removeProjectFileSets(@NonNull Project project) {
        myIndexableSets.removeIf(p -> p.second.equals(project));
    }

    boolean processChangedFiles(@NotNull Project project, @NotNull Processor<? super VirtualFile> processor) {
        // can be performance critical, better to use cycle instead of streams
        // avoid missing files when events are processed concurrently
        Iterator<VirtualFile> iterator = Iterators.concat(
                getChangedFilesCollector().getEventMerger().getChangedFiles(),
                getChangedFilesCollector().getFilesToUpdate()
        );

        HashSet<VirtualFile> checkedFiles = new HashSet<>();
        Predicate<VirtualFile> filterPredicate = filesToBeIndexedForProjectCondition(project);

        while (iterator.hasNext()) {
            VirtualFile virtualFile = iterator.next();
            if (filterPredicate.test(virtualFile) && !checkedFiles.contains(virtualFile)) {
                checkedFiles.add(virtualFile);
                if (!processor.process(virtualFile)) return false;
            }
        }

        return true;
    }

    public RegisteredIndexes getRegisteredIndexes() {
        return myRegisteredIndexes;
    }

    public void doTransientStateChangeForFile(int fileId, @NotNull VirtualFile file) {
        clearUpToDateIndexesForUnsavedOrTransactedDocs();

        Document document = myFileDocumentManager.getCachedDocument(file);
        if (document != null && myFileDocumentManager.isDocumentUnsaved(document)) {   // will be reindexed in indexUnsavedDocuments
            myLastIndexedDocStamps.clearForDocument(document); // Q: non psi indices
            document.putUserData(ourFileContentKey, null);

            return;
        }

        Collection<ID<?, ?>> contentDependentIndexes = ContainerUtil.intersection(IndexingStamp.getNontrivialFileIndexedStates(fileId),
                myRegisteredIndexes.getRequiringContentIndices());

        removeTransientFileDataFromIndices(contentDependentIndexes, fileId, file);
        for (ID<?, ?> candidate : contentDependentIndexes) {
            getIndex(candidate).invalidateIndexedStateForFile(fileId);
        }
        IndexingStamp.flushCache(fileId);

        getChangedFilesCollector().scheduleForUpdate(file);
    }

    public void doInvalidateIndicesForFile(int fileId, @NotNull VirtualFile file) {
        IndexingFlag.cleanProcessedFlagRecursively(file);

        List<ID<?, ?>> nontrivialFileIndexedStates = IndexingStamp.getNontrivialFileIndexedStates(fileId);

        // transient index value can depend on disk value because former is diff to latter
        // it doesn't matter content hanged or not: indices might depend on file name too
        removeTransientFileDataFromIndices(nontrivialFileIndexedStates, fileId, file);

        // file was removed
        for (ID<?, ?> indexId : nontrivialFileIndexedStates) {
            if (!myRegisteredIndexes.isContentDependentIndex(indexId)) {
                removeSingleIndexValue(indexId, fileId);
            }
        }

        if (!file.isDirectory()) {
            // its data should be (lazily) wiped for every index
            getChangedFilesCollector().scheduleForUpdate(new DeletedVirtualFileStub(((VirtualFileWithId) file).getId()));
        }
        else {
            getChangedFilesCollector().removeScheduledFileFromUpdate(file); // no need to update it anymore
        }
    }

    public void scheduleFileForIndexing(int fileId,
                                        @NotNull VirtualFile file,
                                        boolean contentChange) {
        if (ensureFileBelongsToIndexableFilter(fileId, file) == FileAddStatus.SKIPPED) {
            doInvalidateIndicesForFile(fileId, file);
            return;
        }
//
        List<ID<?, ?>> nontrivialFileIndexedStates =
                IndexingStamp.getNontrivialFileIndexedStates(fileId);

        // transient index value can depend on disk value because former is diff to latter
        // it doesn't matter content hanged or not: indices might depend on file name too
        removeTransientFileDataFromIndices(nontrivialFileIndexedStates, fileId, file);

        // handle 'content-less' indices separately
        boolean fileIsDirectory = file.isDirectory();
        IndexedFileImpl indexedFile = new IndexedFileImpl(file, findProjectForFileId(fileId));

        FileContent fileContent = null;
        for (ID<?, ?> indexId : contentChange ? Collections.singleton(FileTypeIndex.NAME) :
                getContentLessIndexes(
                fileIsDirectory)) {
            if (acceptsInput(indexId, indexedFile)) {
                if (fileContent == null) {
                    fileContent = new IndexedFileWrapper(indexedFile);
                }
                updateSingleIndex(indexId, file, fileId, fileContent);
            }
        }

        // For 'normal indices' schedule the file for update and reset stamps for all affected
        //indices (there can be client that used indices between before and after events, in such
        // case
        //indices are up to date due to force update
        // with old content)
        if (!fileIsDirectory) {
            if (!file.isValid() || isTooLarge(file, file.getLength(), Collections.emptySet())) {
                // large file might be scheduled for update in before event when its size was
                // not large
                getChangedFilesCollector().scheduleForUpdate(new DeletedVirtualFileStub(
                        FileIdStorage.getId(file)));
            } else {
                ourFileToBeIndexed.set(file);
                try {
//                    FileTypeManagerEx.getInstanceEx().freezeFileTypeTemporarilyIn(file, () -> {
//
//                    });

                    List<ID<?, ?>> candidates = getAffectedIndexCandidates(indexedFile);

                    boolean scheduleForUpdate = false;

                    for (int i = 0, size = candidates.size(); i < size; ++i) {
                        final ID<?, ?> indexId = candidates.get(i);
                        if (needsFileContentLoading(indexId) &&
                            acceptsInput(indexId, indexedFile)) {
                            getIndex(indexId).invalidateIndexedStateForFile(fileId);
                            scheduleForUpdate = true;
                        }
                    }

                    if (scheduleForUpdate) {
                        IndexingStamp.flushCache(fileId);
                        getChangedFilesCollector().scheduleForUpdate(file);
                    } else {
                        IndexingFlag.setFileIndexed(file);
                    }
                } finally {
                    ourFileToBeIndexed.remove();
                }
            }
        } else {
            IndexingFlag.setFileIndexed(file);
        }
    }

    @NotNull List<ID<?, ?>> getAffectedIndexCandidates(@NotNull IndexedFile indexedFile) {
        if (indexedFile.getFile().isDirectory()) {
            return isProjectOrWorkspaceFile(indexedFile.getFile(),
                    null) ? Collections.emptyList() :
                    myRegisteredIndexes.getIndicesForDirectories();
        }
        FileType fileType = indexedFile.getFileType();
        if (fileType instanceof SubstitutedFileType) {
            fileType = ((SubstitutedFileType)fileType).getOriginalFileType();
        }
        if (isProjectOrWorkspaceFile(indexedFile.getFile(), fileType)) return Collections
        .emptyList();

        return getState().getFileTypesForIndex(fileType);
    }

    public static boolean isProjectOrWorkspaceFile(@NotNull VirtualFile file, @Nullable FileType fileType) {
        file = file.getParent();
        while (file != null) {
            if (Project.DIRECTORY_STORE_FOLDER.equals(file.getName())) {
                return true;
            }
            file = file.getParent();
        }
        return false;
    }


    void clearUpToDateIndexesForUnsavedOrTransactedDocs() {
        if (!myUpToDateIndicesForUnsavedOrTransactedDocuments.isEmpty()) {
            myUpToDateIndicesForUnsavedOrTransactedDocuments.clear();
        }
    }

    private static void cleanFileContent(FileContentImpl fc, PsiFile psiFile) {
        if (fc == null) {
            return;
        }
        if (psiFile != null) {
            psiFile.putUserData(PsiFileImpl.BUILDING_STUB, null);
        }
        fc.putUserData(IndexingDataKeys.PSI_FILE, null);
    }

    private static void initFileContent(@NotNull FileContentImpl fc, PsiFile psiFile) {
        if (psiFile != null) {
            psiFile.putUserData(PsiFileImpl.BUILDING_STUB, true);
            fc.putUserData(IndexingDataKeys.PSI_FILE, psiFile);
        }
    }


    void updateSingleIndex(@NotNull ID<?, ?> indexId,
                           @NotNull VirtualFile file,
                           int inputId,
                           @NotNull FileContent currentFC) {
        SingleIndexValueApplier<?> applier = createSingleIndexValueApplier(indexId,
                file,
                inputId,
                currentFC,
                isWritingIndexValuesSeparatedFromCounting());
        if (applier != null) {
            applier.apply();
        }
    }

    <FileIndexMetaData> SingleIndexValueApplier<FileIndexMetaData> createSingleIndexValueApplier(@NotNull ID<?, ?> indexId,
                                                                                                 @NotNull VirtualFile file,
                                                                                                 int inputId,
                                                                                                 @NotNull FileContent currentFC,
                                                                                                 boolean writeValuesSeparately) {
        if (doTraceStubUpdates(indexId)) {
            LOG.info("index " +
                     indexId +
                     " update requested for " +
                     getFileInfoLogString(inputId, file, currentFC));
        }

        if (!myRegisteredIndexes.isExtensionsDataLoaded()) {
            reportUnexpectedAsyncInitState();
        }
        if (!RebuildStatus.isOk(indexId) && !myIsUnitTestMode) {
            return null; // the index is scheduled for rebuild, no need to update
        }

        increaseLocalModCount();

        //noinspection unchecked
        UpdatableIndex<?, ?, FileContent, FileIndexMetaData> index =
                (UpdatableIndex<?, ?, FileContent, FileIndexMetaData>) getIndex(indexId);

        ensureFileBelongsToIndexableFilter(inputId, file);

        if (currentFC instanceof FileContentImpl &&
            FileBasedIndex.ourSnapshotMappingsEnabled &&
            (((FileBasedIndexExtension<?, ?>) index.getExtension()).hasSnapshotMapping() ||
             ((FileBasedIndexExtension<?, ?>) index.getExtension()).canBeShared())) {
            // Optimization: initialize indexed file hash eagerly. The hash is calculated by raw
            // content bytes.
            // If we pass the currentFC to an indexer that calls "FileContentImpl.getContentAsText",
            // the raw bytes will be converted to text and assigned to null.
            // Then, to compute the hash, the reverse conversion will be necessary.
            // To avoid this extra conversion, let's initialize the hash eagerly.
            IndexedHashesSupport.getOrInitIndexedHash((FileContentImpl) currentFC);
        }

        markFileIndexed(file, currentFC);

        try {
            Supplier<Boolean> storageUpdate;
            long evaluatingIndexValueApplierTime = System.nanoTime();
            FileIndexMetaData fileIndexMetaData = index.getFileIndexMetaData(currentFC);
            try {
                storageUpdate = index.mapInputAndPrepareUpdate(inputId, currentFC);
            }catch (MapReduceIndexMappingException e) {
                Throwable cause = e.getCause();
                if (cause instanceof SnapshotInputMappingException) {
                    requestRebuild(indexId, e);
                    return null;
                }
                setIndexedState(index, currentFC, inputId, false);
//                BrokenIndexingDiagnostics.INSTANCE.getExceptionListener().onFileIndexMappingFailed(
//                        inputId,
//                        currentFC.getFile(),
//                        currentFC.getFileType(),
//                        indexId,
//                        e
//                );
                return null;
            } finally {
                evaluatingIndexValueApplierTime = System.nanoTime() - evaluatingIndexValueApplierTime;
            }

            SingleIndexValueApplier<FileIndexMetaData> applier = new SingleIndexValueApplier<>(
                    this,
                    indexId,
                    inputId,
                    fileIndexMetaData,
                    storageUpdate,
                    file,
                    currentFC,
                    evaluatingIndexValueApplierTime
            );

            if (!writeValuesSeparately && !applier.applyImmediately()) {
                return null;
            }
            return applier;
        } catch (RuntimeException exception) {
            requestIndexRebuildOnException(exception, indexId);
            return null;
        } finally {
            unmarkBeingIndexed();
        }
    }

    static void setIndexedState(UpdatableIndex<?, ?, FileContent, ?> index,
                                @NotNull IndexedFile currentFC,
                                int inputId,
                                boolean indexWasProvided) {
//        if (index instanceof FileBasedIndexInfrastructureExtensionUpdatableIndex) {
//            ((FileBasedIndexInfrastructureExtensionUpdatableIndex<?, ?, ?, ?>)index)
//                    .setIndexedStateForFile(inputId, currentFC, indexWasProvided);
//        }
//        else {
            index.setIndexedStateForFile(inputId, currentFC);
//        }
    }

    @NotNull Collection<ID<?, ?>> getContentLessIndexes(boolean isDirectory) {
        return isDirectory ? myRegisteredIndexes.getIndicesForDirectories() :
                myRegisteredIndexes.getNotRequiringContentIndices();
    }

    public boolean needsFileContentLoading(@NotNull ID<?, ?> indexId) {
        return myRegisteredIndexes.isContentDependentIndex(indexId);
    }

    @Nullable
    public Project findProjectForFileId(int fileId) {
        return myIndexableFilesFilterHolder.findProjectForFile(fileId);
    }

    @NotNull
    private FileAddStatus ensureFileBelongsToIndexableFilter(int fileId,
                                                             @NotNull VirtualFile file) {
        return myIndexableFilesFilterHolder.addFileId(fileId, () -> getContainingProjects(file));
    }

    public @NotNull Set<Project> getContainingProjects(@NotNull VirtualFile file) {
        Project project = ProjectCoreUtil.theOnlyOpenProject();
        if (project != null) {
            return belongsToIndexableFiles(file) ? Collections.singleton(project) :
                    Collections.emptySet();
        } else {
            Set<Project> projects = null;
            for (Pair<IndexableFileSet, Project> set : myIndexableSets) {
                if ((projects == null || !projects.contains(set.second)) &&
                    set.first.isInSet(file)) {
                    if (projects == null) {
                        projects = new SmartHashSet<>();
                    }
                    projects.add(set.second);
                }
            }
            return ContainerUtil.notNullize(projects);
        }
    }

    public boolean acceptsInput(@NotNull ID<?, ?> indexId, @NotNull IndexedFile indexedFile) {
        InputFilter filter = getInputFilter(indexId);
        return acceptsInput(filter, indexedFile);
    }

    private InputFilter getInputFilter(@NotNull ID<?, ?> indexId) {
        if (!myRegisteredIndexes.isInitialized()) {
            // 1. early vfs event that needs invalidation
            // 2. pushers that do synchronous indexing for contentless indices
            waitUntilIndicesAreInitialized();
        }

        return getState().getInputFilter(indexId);
    }


    public void removeDataFromIndicesForFile(int fileId,
                                             @NotNull VirtualFile file,
                                             @NotNull String cause) {
        VfsEventsMerger.tryLog("REMOVE", file, () -> "cause=" + cause);

        VirtualFile originalFile =
                file instanceof DeletedVirtualFileStub ?
                        ((DeletedVirtualFileStub) file).getOriginalFile() : file;
        final List<ID<?, ?>> states = IndexingStamp.getNontrivialFileIndexedStates(fileId);

        if (!states.isEmpty()) {
            ProgressManager.getInstance()
                    .executeNonCancelableSection(() -> removeFileDataFromIndices(states,
                            fileId,
                            originalFile));
        }

//        if (file instanceof VirtualFileSystemEntry && file.isValid()) {
//            cleanProcessingFlag(file);
//        }
        boolean isValid =
                file instanceof DeletedVirtualFileStub ?
                        ((DeletedVirtualFileStub) file).isOriginalValid() : file.isValid();
        if (!isValid) {
            getIndexableFilesFilterHolder().removeFile(fileId);
        }
    }

    public void removeFileDataFromIndices(@NotNull Collection<? extends ID<?, ?>> indexIds,
                                          int fileId,
                                          @Nullable VirtualFile file) {
        assert ProgressManager.getInstance().isInNonCancelableSection();
        try {
            // document diff can depend on previous value that will be removed
            removeTransientFileDataFromIndices(indexIds, fileId, file);
            Throwable unexpectedError = null;
            for (ID<?, ?> indexId : indexIds) {
                try {
                    removeSingleIndexValue(indexId, fileId);
                } catch (Throwable e) {
                    LOG.info(e);
                    if (unexpectedError == null) {
                        unexpectedError = e;
                    }
                }
            }

            if (unexpectedError != null) {
                LOG.error(unexpectedError);
            }
        } finally {
            IndexingStamp.flushCache(fileId);
        }
    }

    void increaseLocalModCount() {
        myLocalModCount.incrementAndGet();
    }

    private void removeSingleIndexValue(@NotNull ID<?, ?> indexId, int inputId) {
        boolean isWritingValuesSeparately = isWritingIndexValuesSeparatedFromCounting();
        SingleIndexValueRemover remover =
                createSingleIndexRemover(indexId, null, null, inputId, isWritingValuesSeparately);
        if (remover != null && isWritingValuesSeparately) {
            remover.remove();
        }
    }

    //null in case index value removal is not necessary or immediate removal failed
    @Nullable
    private SingleIndexValueRemover createSingleIndexRemover(@NotNull ID<?, ?> indexId,
                                                             @Nullable VirtualFile file,
                                                             @Nullable FileContent fileContent,
                                                             int inputId,
                                                             boolean isWritingValuesSeparately) {
        if (doTraceStubUpdates(indexId)) {
            LOG.info("index " +
                     indexId +
                     " deletion requested for " +
                     getFileInfoLogString(inputId, file, fileContent));
        }
        if (!myRegisteredIndexes.isExtensionsDataLoaded()) {
            reportUnexpectedAsyncInitState();
        }
        if (!RebuildStatus.isOk(indexId) && !myIsUnitTestMode) {
            return null; // the index is scheduled for rebuild, no need to update
        }
        SingleIndexValueRemover remover = new SingleIndexValueRemover(this,
                indexId,
                file,
                fileContent,
                inputId,
                isWritingValuesSeparately);
        if (!isWritingValuesSeparately && !remover.remove()) {
            return null;
        }
        return remover;
    }

    private void removeTransientFileDataFromIndices(@NotNull Collection<? extends ID<?, ?>> indices,
                                                    int inputId,
                                                    @Nullable VirtualFile file) {
        for (ID<?, ?> indexId : indices) {
            getIndex(indexId).removeTransientDataForFile(inputId);
        }

        Document document = file == null ? null : myFileDocumentManager.getCachedDocument(file);
        if (document != null) {
            myLastIndexedDocStamps.clearForDocument(document);
            document.putUserData(ourFileContentKey, null);
        }

        clearUpToDateIndexesForUnsavedOrTransactedDocs();
    }

    private static final Key<WeakReference<Pair<FileContentImpl, Long>>> ourFileContentKey =
            Key.create("unsaved.document.index.content");
    private final ThreadLocal<Boolean> myReentrancyGuard =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    public void cleanupMemoryStorage(boolean skipContentDependentIndexes) {
        myLastIndexedDocStamps.clear();
        if (myRegisteredIndexes == null) {
            // unsaved doc is dropped while plugin load/unload-ing
            return;
        }
        IndexConfiguration state = myRegisteredIndexes.getState();
        if (state == null) {
            // avoid waiting for end of indices initialization (IDEA-173382)
            // in memory content will appear on indexing (in read action) and here is event
            // dispatch (write context)
            return;
        }
        for (ID<?, ?> indexId : state.getIndexIDs()) {
            if (skipContentDependentIndexes &&
                myRegisteredIndexes.isContentDependentIndex(indexId)) {
                continue;
            }
            UpdatableIndex<?, ?, FileContent, ?> index = getIndex(indexId);
            index.cleanupMemoryStorage();
        }
    }

    private static boolean isWritingIndexValuesSeparatedFromCounting() {
        return ourWritingIndexValuesSeparatedFromCounting;
    }

    static boolean isWritingIndexValuesSeparatedFromCountingForContentIndependentIndexes() {
        return ourWritingIndexValuesSeparatedFromCounting &&
               ourWritingIndexValuesSeparatedFromCountingForContentIndependentIndexes;
    }

    static <K, V> void registerIndexer(@NonNull final FileBasedIndexExtension<K, V> extension,
                                       @NonNull IndexConfiguration state,
                                       @NonNull IndexVersionRegistrationSink versionRegistrationStatusSink,
                                       @NonNull IntSet staleInputIdSink,
                                       @NonNull IntSet dirtyFiles) throws Exception {
        ID<K, V> name = extension.getName();
        int version = getIndexExtensionVersion(extension);

        IndexVersion.IndexVersionDiff diff = IndexVersion.versionDiffers(name, version);
        versionRegistrationStatusSink.setIndexVersionDiff(name, diff);
        if (diff != IndexVersion.IndexVersionDiff.UP_TO_DATE) {
            deleteWithRenamingIfExists(IndexInfrastructure.getPersistentIndexRootDir(name));
            deleteWithRenamingIfExists(IndexInfrastructure.getIndexRootDir(name));
            IndexVersion.rewriteVersion(name, version);

            try {
                for (FileBasedIndexInfrastructureExtension ex :
                        FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
                    ex.onFileBasedIndexVersionChanged(name);
                }
            } catch (Exception e) {
                LOG.error(e);
            }
        }

        initIndexStorage(extension,
                version,
                state,
                versionRegistrationStatusSink,
                staleInputIdSink,
                dirtyFiles);
    }

    @NotNull
    private static <K, V> UpdatableIndex<K, V, FileContent, ?> createIndex(@NotNull FileBasedIndexExtension<K, V> extension,
                                                                           @NotNull VfsAwareIndexStorageLayout<K, V> layout)
            throws StorageException, IOException {
//        if (FileBasedIndexExtension.USE_VFS_FOR_FILENAME_INDEX && extension.getName() == FilenameIndex.NAME) {
//            return new EmptyIndex<>(extension);
//        }
        if (extension instanceof CustomImplementationFileBasedIndexExtension) {
            @SuppressWarnings("unchecked") UpdatableIndex<K, V, FileContent, ?> index =
                    ((CustomImplementationFileBasedIndexExtension<K, V>)extension).createIndexImplementation(extension, layout);
            return index;
        }
        else {
            return TransientFileContentIndex.createIndex(extension, layout);
        }
    }

    private static <K, V> void initIndexStorage(@NonNull FileBasedIndexExtension<K, V> extension,
                                                int version,
                                                @NonNull IndexConfiguration state,
                                                @NonNull IndexVersionRegistrationSink registrationStatusSink,
                                                @NonNull IntSet staleInputIdSink,
                                                @NonNull IntSet dirtyFiles) throws Exception {
        ID<K, V> name = extension.getName();
        Set<FileType> addedTypes;
        InputFilter inputFilter;
        boolean contentHashesEnumeratorOk = true;

        LOG.info("Initializing index storage for ID: " + name.getName());

        try {
            inputFilter = extension.getInputFilter();
            if (inputFilter instanceof FileBasedIndex.FileTypeSpecificInputFilter) {
                addedTypes = new HashSet<>();
                ((FileBasedIndex.FileTypeSpecificInputFilter) inputFilter).registerFileTypesUsedForIndexing(
                        type -> {
                            if (type != null) {
                                addedTypes.add(type);
                            }
                        });
            } else {
                addedTypes = null;
            }

            if (VfsAwareMapReduceIndex.hasSnapshotMapping(extension)) {
//                contentHashesEnumeratorOk = SnapshotHashEnumeratorService.getInstance()
//                .initialize();
            }
        } catch (Exception e) {
            state.registerIndexInitializationProblem(name, e);
            throw e;
        }


        UpdatableIndex<K, V, FileContent, ?> index = null;

        int attemptCount = 2;
        for (int attempt = 0; attempt < attemptCount; attempt++) {
            try {
                VfsAwareIndexStorageLayout<K, V> layout =
                        DefaultIndexStorageLayout.getLayout(extension, contentHashesEnumeratorOk);
                index = createIndex(extension, layout);
                for (FileBasedIndexInfrastructureExtension infrastructureExtension :
                        FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
                    UpdatableIndex<K, V, FileContent, ?> intermediateIndex =
                            infrastructureExtension.combineIndex(extension, index);
                    if (intermediateIndex != null) {
                        index = intermediateIndex;
                    }
                }

                state.registerIndex(name,
                        index,
                        composeInputFilter(inputFilter, (file, project) -> true)
//                                        !GlobalIndexFilter.isExcludedFromIndexViaFilters(
//                                        file,
//                                        name,
//                                        project))
                        ,
                        version + 0,
// GlobalIndexFilter.getFiltersVersion(name),
                        addedTypes);
                break;
            } catch (Exception e) {
                boolean lastAttempt = attempt == attemptCount - 1;

                try {
                    VfsAwareIndexStorageLayout<K, V> layout = DefaultIndexStorageLayout.getLayout(
                            extension,
                            contentHashesEnumeratorOk);
                    layout.clearIndexData();
                } catch (Exception layoutEx) {
                    LOG.error(layoutEx);
                }

                for (FileBasedIndexInfrastructureExtension ext :
                        FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
                    try {
                        ext.resetPersistentState(name);
                    } catch (Exception extEx) {
                        LOG.error(extEx);
                    }
                }

                registrationStatusSink.setIndexVersionDiff(name,
                        new IndexVersion.IndexVersionDiff.CorruptedRebuild(version));
                IndexVersion.rewriteVersion(name, version);

                if (lastAttempt) {
                    state.registerIndexInitializationProblem(name, e);
                    if (extension instanceof CustomImplementationFileBasedIndexExtension) {
                        ((CustomImplementationFileBasedIndexExtension<?, ?>) extension).handleInitializationError(
                                e);
                    }
                    throw e;
                } else if (ApplicationManager.getApplication().isUnitTestMode()) {
                    LOG.error(e);
                } else {
                    String message = "Attempt #" +
                                     attemptCount +
                                     " to initialize index has failed for " +
                                     extension.getName();
                    //noinspection InstanceofCatchParameter
                    if (e instanceof CorruptedException) {
                        LOG.warn(message + " because storage corrupted");
                    } else {
                        LOG.warn(message, e);
                    }
                }
            }
        }

        LOG.info("Done initializing index for ID: " + name.getName() + " index: " + index);

        try {
            if (StubUpdatingIndex.INDEX_ID.equals(extension.getName()) && index != null) {
                staleInputIdSink.addAll(StaleIndexesChecker.checkIndexForStaleRecords(index,
                dirtyFiles, true));
            }
        } catch (Exception e) {
            LOG.error("Exception while checking for stale records", e);
        }

    }

    private static <K, V> int getIndexExtensionVersion(FileBasedIndexExtension<K, V> extension) {
        return 0;
    }

    static void setupWritingIndexValuesSeparatedFromCounting() {
        ourWritingIndexValuesSeparatedFromCounting = SystemProperties.getBooleanProperty(
                "indexing.separate.applying.values.from.counting",
                true);
    }

    static void setupWritingIndexValuesSeparatedFromCountingForContentIndependentIndexes() {
        ourWritingIndexValuesSeparatedFromCountingForContentIndependentIndexes =
                SystemProperties.getBooleanProperty(
                        "indexing.separate.applying.values.from.counting.for.content.independent" +
                        ".indexes",
                        true);
    }

    public static void markFileIndexed(@Nullable VirtualFile file, @Nullable FileContent fc) {
        // TODO restore original assertion
        if (fc != null && (ourIndexedFile.get() != null || ourFileToBeIndexed.get() != null)) {
            throw new AssertionError("Reentrant indexing");
        }
        ourIndexedFile.set(file);
    }

    public static void unmarkBeingIndexed() {
        ourIndexedFile.remove();
    }

    static void markFileWritingIndexes(int fileId) {
        if (/*filePath != null &&*/ ourWritingIndexFile.get() != null) {
            throw new AssertionError("Reentrant writing indices");
        }
        ourWritingIndexFile.set(new IndexWritingFile(fileId));
    }

    static void unmarkWritingIndexes() {
        ourWritingIndexFile.remove();
    }

    static String getFileInfoLogString(int inputId,
                                       @Nullable VirtualFile file,
                                       @Nullable FileContent currentFC) {
        if (file == null && currentFC == null) {
            return String.valueOf(inputId);
        }
        String fileName = currentFC != null ? currentFC.getFileName() : file.getName();
        return fileName + "(id=" + inputId + ")";
    }

    boolean runUpdateForPersistentData(Supplier<Boolean> storageUpdate) {
        return myStorageBufferingHandler.runUpdate(false, () -> ProgressManager.getInstance()
                .computeInNonCancelableSection(storageUpdate::get));
    }

    void requestIndexRebuildOnException(RuntimeException exception, ID<?, ?> indexId) {
        Throwable causeToRebuildIndex = getCauseToRebuildIndex(exception);
        if (causeToRebuildIndex != null) {
            requestRebuild(indexId, exception);
        } else {
            throw exception;
        }
    }

    private static void reportUnexpectedAsyncInitState() {
        LOG.error("Unexpected async indices initialization problem");
    }



    public @NotNull ProjectIndexableFilesFilterHolder getIndexableFilesFilterHolder() {
        return myIndexableFilesFilterHolder;
    }

    @NotNull Collection<VirtualFile> getFilesToUpdate(final Project project) {
        return ContainerUtil.filter(getChangedFilesCollector().getAllFilesToUpdate(),
                filesToBeIndexedForProjectCondition(project)::test);
    }

    @NotNull
    private Predicate<VirtualFile> filesToBeIndexedForProjectCondition(Project project) {
        return virtualFile -> {
            if (!virtualFile.isValid()) {
                return true;
            }
            for (Pair<IndexableFileSet, Project> set : myIndexableSets) {
                final Project proj = set.second;
                if (proj != null && !proj.equals(project)) {
                    continue; // skip this set as associated with a different project
                }
                if (ReadAction.compute(() -> set.first.isInSet(virtualFile))) {
                    return true;
                }
            }
            return false;
        };
    }

    @ApiStatus.Internal
    public void dropNontrivialIndexedStates(int inputId) {
        for (ID<?, ?> id : IndexingStamp.getNontrivialFileIndexedStates(inputId)) {
            dropNontrivialIndexedStates(inputId, id);
        }
    }

    @ApiStatus.Internal
    public void dropNontrivialIndexedStates(int inputId, ID<?, ?> indexId) {
        UpdatableIndex<?, ?, FileContent, ?> index = getIndex(indexId);
        index.invalidateIndexedStateForFile(inputId);
    }

    FileIndexingState shouldIndexFile(@NotNull IndexedFile file, @NotNull ID<?, ?> indexId) {
        if (!acceptsInput(indexId, file)) {
            return getIndexingState(file, indexId) == FileIndexingState.NOT_INDEXED
                    ? FileIndexingState.UP_TO_DATE
                    : FileIndexingState.OUT_DATED;
        }
        return getIndexingState(file, indexId);
    }

    @NotNull
    FileIndexingState getIndexingState(@NotNull IndexedFile file, @NotNull ID<?, ?> indexId) {
        VirtualFile virtualFile = file.getFile();
        if (isMock(virtualFile)) return FileIndexingState.NOT_INDEXED;

        return getIndex(indexId).getIndexingStateForFile(FileIdStorage.getAndStoreId(virtualFile), file);
    }

    private boolean isPendingDeletionFileAppearedInIndexableFilter(int fileId, @NotNull VirtualFile file) {
        if (file instanceof DeletedVirtualFileStub) {
            DeletedVirtualFileStub deletedFileStub = (DeletedVirtualFileStub) file;
            return deletedFileStub.isOriginalValid() &&
                   ensureFileBelongsToIndexableFilter(fileId, deletedFileStub.getOriginalFile()) !=
                   FileAddStatus.SKIPPED;
        }
        return false;
    }

    @ApiStatus.Internal
    @NotNull
    public FileIndexesValuesApplier indexFileContent(@Nullable Project project,
                                                     @NotNull CachedFileContent content,
                                                     @Nullable FileType cachedFileType) {
        ProgressManager.checkCanceled();
        VirtualFile file = content.getVirtualFile();
        final int fileId = getFileId(file);

        boolean writeIndexValuesSeparately = isWritingIndexValuesSeparatedFromCounting();
        boolean isValid = file.isValid();
        // if file was scheduled for update due to vfs events then it is present in myFilesToUpdate
        // in this case we consider that current indexing (out of roots backed CacheUpdater) will cover its content
        if (file.isValid() && content.getTimeStamp() != file.getTimeStamp()) {
            content = new CachedFileContent(file);
        }
        if (isPendingDeletionFileAppearedInIndexableFilter(fileId, file)) {
            file = ((DeletedVirtualFileStub)file).getOriginalFile();
            assert file != null;
            content = new CachedFileContent(file);
            isValid = file.isValid();
            dropNontrivialIndexedStates(fileId);
            cachedFileType = file.getFileType();
        }

        FileIndexesValuesApplier applier;
        if (!isValid || isTooLarge(file)) {
            ProgressManager.checkCanceled();
            applier = new FileIndexesValuesApplier(this, fileId, file, Collections.emptyList(), Collections.emptyList(),
                    true, true, writeIndexValuesSeparately,
                    cachedFileType == null ? file.getFileType() : cachedFileType, false);
        }else {
            applier = doIndexFileContent(project, content, cachedFileType, writeIndexValuesSeparately);
        }

        applier.applyImmediately(file, isValid);
        return applier;
    }

    @NotNull
    private FileIndexesValuesApplier doIndexFileContent(@Nullable Project project,
                                                        @NotNull CachedFileContent content,
                                                        @Nullable FileType cachedFileType,
                                                        boolean writeIndexSeparately) {
        ProgressManager.checkCanceled();
        final VirtualFile file = content.getVirtualFile();
        Ref<Boolean> setIndexedStatus = Ref.create(Boolean.TRUE);
        Ref<FileType> fileTypeRef = Ref.create();

        //todo check file still from project
        int inputId = getFileId(file);
        Project guessedProject = project != null ? project : findProjectForFileId(inputId);
        IndexedFileImpl indexedFile = new IndexedFileImpl(file, guessedProject);

        List<SingleIndexValueApplier<?>> appliers = new ArrayList<>();
        List<SingleIndexValueRemover> removers = new ArrayList<>();

        ProgressManager.checkCanceled();
        FileContentImpl fc = null;

        Set<ID<?, ?>> currentIndexedStates = new HashSet<>(IndexingStamp.getNontrivialFileIndexedStates(inputId));
        List<ID<?, ?>> affectedIndexCandidates = getAffectedIndexCandidates(indexedFile);
        for (int i = 0, size = affectedIndexCandidates.size(); i < size; ++i) {
            ID<?, ?> indexId = affectedIndexCandidates.get(i);
//            if (FileBasedIndexScanUtil.isManuallyManaged(indexId)) continue;
            ProgressManager.checkCanceled();

            if (fc == null) {
                fc = (FileContentImpl)FileContentImpl.createByContent(file,
                        content::getBytesOrEmpty, guessedProject);
                fc.setSubstituteFileType(indexedFile.getFileType());
                ProgressManager.checkCanceled();

                fileTypeRef.set(fc.getFileType());

                ProgressManager.checkCanceled();
            }

            boolean update;
            boolean acceptedAndRequired = acceptsInput(indexId, fc) && getIndexingState(fc, indexId).updateRequired();
            if (acceptedAndRequired) {
                update = RebuildStatus.isOk(indexId);
                if (!update) {
                    setIndexedStatus.set(Boolean.FALSE);
                    currentIndexedStates.remove(indexId);
                }
            }
            else {
                update = false;
            }

            if (!update && doTraceStubUpdates(indexId)) {
                String reason;
                if (acceptedAndRequired) {
                    reason = "index is required to rebuild, and indexing does not update such";
                }
                else if (acceptsInput(indexId, fc)) {
                    reason = "update is not required";
                }
                else {
                    reason = "file is not accepted by index";
                }

                LOG.info("index " + indexId + " should not be updated for " + fc.getFileName() + " because " + reason);
            }

            if (update) {
                ProgressManager.checkCanceled();
                SingleIndexValueApplier<?> singleIndexValueApplier =
                        createSingleIndexValueApplier(indexId, file, inputId, fc, writeIndexSeparately);
                if (singleIndexValueApplier == null) {
                    setIndexedStatus.set(Boolean.FALSE);
                }
                else {
                    appliers.add(singleIndexValueApplier);
                }
                currentIndexedStates.remove(indexId);
            }
        }

        boolean shouldClearAllIndexedStates = fc == null;
        for (ID<?, ?> indexId : currentIndexedStates) {
            ProgressManager.checkCanceled();
            if (shouldClearAllIndexedStates || getIndex(indexId).getIndexingStateForFile(inputId, fc).updateRequired()) {
                ProgressManager.checkCanceled();
                SingleIndexValueRemover remover = createSingleIndexRemover(indexId, file, fc, inputId, writeIndexSeparately);
                if (remover == null) {
                    setIndexedStatus.set(Boolean.FALSE);
                }
                else {
                    removers.add(remover);
                }
            }
        }

        fileTypeRef.set(fc != null ? fc.getFileType() : file.getFileType());

        file.putUserData(IndexingDataKeys.REBUILD_REQUESTED, null);
        return new FileIndexesValuesApplier(this,
                inputId, file, appliers, removers, false, setIndexedStatus.get(), writeIndexSeparately,
                fileTypeRef.get(), doTraceSharedIndexUpdates()
        );
    }
}
