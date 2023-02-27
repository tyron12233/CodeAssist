package org.jetbrains.kotlin.com.intellij.util.indexing;

import static org.jetbrains.kotlin.com.intellij.util.io.MeasurableIndexStore.keysCountApproximatelyIfPossible;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicator;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil;
import org.jetbrains.kotlin.com.intellij.openapi.roots.ContentIterator;
import org.jetbrains.kotlin.com.intellij.openapi.util.*;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.CompactVirtualFileSet;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointerManagerEx;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.psi.SingleRootFileViewProvider;
import org.jetbrains.kotlin.com.intellij.psi.search.EverythingGlobalScope;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.impl.VirtualFileEnumeration;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatingIndex;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.kotlin.com.intellij.util.*;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.Stack;
import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer.ValueIterator;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexDebugProperties;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.InvertedIndexValueIterator;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapReduceIndexMappingException;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesContributor;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesDeduplicateFilter;
import org.jetbrains.kotlin.com.intellij.util.indexing.roots.IndexableFilesIterator;
import org.jetbrains.kotlin.com.intellij.util.indexing.snapshot.SnapshotInputMappingException;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.*;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.objects.ObjectIterators;

import java.io.IOException;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public abstract class FileBasedIndexEx extends FileBasedIndex {
    public static final boolean DO_TRACE_STUB_INDEX_UPDATE =
            Boolean.getBoolean("idea.trace.stub.index.update");
    @SuppressWarnings("SSBasedInspection")
    private static final ThreadLocal<Stack<DumbModeAccessType>> ourDumbModeAccessTypeStack =
            ThreadLocal.withInitial(() -> new org.jetbrains.kotlin.com.intellij.util.containers.Stack<>());
    private static final RecursionGuard<Object> ourIgnoranceGuard =
            RecursionManager.createGuard("ignoreDumbMode");
    private volatile boolean myTraceIndexUpdates;
    private volatile boolean myTraceStubIndexUpdates;
    private volatile boolean myTraceSharedIndexUpdates;

    //
    public boolean doTraceIndexUpdates() {
        return myTraceIndexUpdates;
    }

    //
    public boolean doTraceStubUpdates(@NonNull ID<?, ?> indexId) {
        return myTraceStubIndexUpdates && indexId.equals(StubUpdatingIndex.INDEX_ID);
    }

    //
    boolean doTraceSharedIndexUpdates() {
        return myTraceSharedIndexUpdates;
    }

    //
    public void loadIndexes() {
        myTraceIndexUpdates =
                SystemProperties.getBooleanProperty("trace.file.based.index.update", false);
        myTraceStubIndexUpdates =
                SystemProperties.getBooleanProperty("trace.stub.index.update", false);
        myTraceSharedIndexUpdates =
                SystemProperties.getBooleanProperty("trace.shared.index.update", false);
    }

    @NonNull
    public abstract IntPredicate getAccessibleFileIdFilter(@Nullable Project project);

    @Nullable
    public abstract IdFilter extractIdFilter(@Nullable GlobalSearchScope scope,
                                             @Nullable Project project);

    @Nullable
    public abstract IdFilter projectIndexableFiles(@Nullable Project project);

    @NonNull
    public abstract <K, V> UpdatableIndex<K, V, FileContent, ?> getIndex(ID<K, V> indexId);

    public abstract void waitUntilIndicesAreInitialized();

    /**
     * @return true if index can be processed after it or
     * false if no need to process it because, for example, scope is empty or index is going to
     * rebuild.
     */
    public abstract <K> boolean ensureUpToDate(@NonNull final ID<K, ?> indexId,
                                               @Nullable Project project,
                                               @Nullable GlobalSearchScope filter,
                                               @Nullable VirtualFile restrictedFile);

    @Override
    @NonNull
    public <K, V> List<V> getValues(@NonNull final ID<K, V> indexId,
                                    @NonNull K dataKey,
                                    @NonNull final GlobalSearchScope filter) {
        @Nullable Iterator<VirtualFile> restrictToFileIt = extractSingleFileOrEmpty(filter);

        final List<V> values = new SmartList<>();
        ValueProcessor<V> processor = (file, value) -> {
            values.add(value);
            return true;
        };
        if (restrictToFileIt != null) {
            VirtualFile restrictToFile =
                    restrictToFileIt.hasNext() ? restrictToFileIt.next() : null;
            if (restrictToFile == null) {
                return Collections.emptyList();
            }
            processValuesInOneFile(indexId, dataKey, restrictToFile, filter, processor);
        } else {
            processValuesInScope(indexId, dataKey, true, filter, null, processor);
        }
        return values;
    }

    @Override
    @NonNull
    public <K> Collection<K> getAllKeys(@NonNull final ID<K, ?> indexId, @NonNull Project project) {
        Set<K> allKeys = new HashSet<>();
        processAllKeys(indexId, Processors.cancelableCollectProcessor(allKeys), project);
        return allKeys;
    }

    @Override
    public <K> boolean processAllKeys(@NonNull ID<K, ?> indexId,
                                      @NonNull Processor<? super K> processor,
                                      @Nullable Project project) {
        return processAllKeys(indexId,
                processor,
                project == null ? new EverythingGlobalScope() : GlobalSearchScope.everythingScope(
                        project),
                null);
    }

    @Override
    public <K> boolean processAllKeys(@NonNull ID<K, ?> indexId,
                                      @NonNull Processor<? super K> processor,
                                      @NonNull GlobalSearchScope scope,
                                      @Nullable IdFilter idFilter) {
//    var trace = lookupAllKeysStarted(indexId)
//      .withProject(scope.getProject());
        try {
            waitUntilIndicesAreInitialized();
            UpdatableIndex<K, ?, FileContent, ?> index = getIndex(indexId);
            if (!ensureUpToDate(indexId, scope.getProject(), scope, null)) {
                return true;
            }

//      trace.indexValidationFinished();

            IdFilter idFilterAdjusted =
                    idFilter == null ? extractIdFilter(scope, scope.getProject()) : idFilter;
//      trace.totalKeysIndexed(keysCountApproximatelyIfPossible(index));

            getLogger().info("Processing index id: " + indexId.getName() + " index: " + index);
            return index.processAllKeys(processor, scope, idFilterAdjusted);
        } catch (StorageException e) {
//      trace.lookupFailed();
            scheduleRebuild(indexId, e);
        } catch (RuntimeException e) {
//      trace.lookupFailed();
            final Throwable cause = e.getCause();
            if (cause instanceof StorageException || cause instanceof IOException) {
                scheduleRebuild(indexId, cause);
            } else {
                throw e;
            }
        } finally {
            //Not using try-with-resources because in case of exceptions are thrown, .close()
            // needs to be called _after_ catch,
            //  so .lookupFailed() is invoked on a not-yet-closed trace -- but TWR does the
            //  opposite: first close resources, then
            //  do all catch/finally blocks
//      trace.close();
        }

        return false;
    }

    @NonNull
    @Override
    public <K, V> Map<K, V> getFileData(@NonNull ID<K, V> id,
                                        @NonNull VirtualFile virtualFile,
                                        @NonNull Project project) {
//        if (!(virtualFile instanceof VirtualFileWithId)) {
//            return Collections.emptyMap();
//        }
        int fileId = getFileId(virtualFile);

        if (getAccessibleFileIdFilter(project).test(fileId)) {
            Map<K, V> map = processExceptions(id,
                    virtualFile,
                    GlobalSearchScope.fileScope(project, virtualFile),
                    index -> {

                        if ((IndexDebugProperties.DEBUG &&
                             !ApplicationManager.getApplication().isUnitTestMode()) &&
                            !((FileBasedIndexExtension<K, V>) index.getExtension()).needsForwardIndexWhenSharing()) {
                            getLogger().error("Index extension " +
                                              id +
                                              " doesn't require forward index but accesses it");
                        }

                        return index.getIndexedFileData(fileId);
                    });
            if (map == null) {
                return Collections.emptyMap();
            }
            return map;
        }
        return Collections.emptyMap();
    }

    @Override
    public <V> V getSingleEntryIndexData(@NonNull ID<Integer, V> id,
                                         @NonNull VirtualFile virtualFile,
                                         @NonNull Project project) {
        if (!(getIndex(id).getExtension() instanceof SingleEntryFileBasedIndexExtension)) {
            throw new IllegalArgumentException("'" +
                                               id +
                                               "' index is not a SingleEntryFileBasedIndex");
        }
        Map<Integer, V> data = getFileData(id, virtualFile, project);
        if (data.isEmpty()) {
            return null;
        }
        if (data.size() == 1) {
            return data.values().iterator().next();
        }
        throw new IllegalStateException("Invalid single entry index data '" + id + "'");
    }

    @Override
    @NonNull
    public <K, V> Collection<VirtualFile> getContainingFiles(@NonNull ID<K, V> indexId,
                                                             @NonNull K dataKey,
                                                             @NonNull GlobalSearchScope filter) {
        Iterator<VirtualFile> containingFilesIterator =
                getContainingFilesIterator(indexId, dataKey, filter);
        Set<VirtualFile> set = new HashSet<>();
        containingFilesIterator.forEachRemaining(set::add);
        return set;
    }

    @Override
    public @NonNull <K, V> Iterator<VirtualFile> getContainingFilesIterator(@NonNull ID<K, V> indexId,
                                                                            @NonNull K dataKey,
                                                                            @NonNull GlobalSearchScope scope) {
        Project project = scope.getProject();
//        try (var trace = lookupEntriesStarted(indexId)) {
//            trace.keysWithAND(1).withProject(project);

//        if (project instanceof LightEditCompatible) {
//            return Collections.emptyIterator();
//        }
        @Nullable Iterator<VirtualFile> restrictToFileIt = extractSingleFileOrEmpty(scope);
        if (restrictToFileIt != null) {
            VirtualFile restrictToFile =
                    restrictToFileIt.hasNext() ? restrictToFileIt.next() : null;
            if (restrictToFile == null) {
                return Collections.emptyIterator();
            }
            return !processValuesInOneFile(indexId,
                    dataKey,
                    restrictToFile,
                    scope,
                    (f, v) -> false) ? Collections.singleton(restrictToFile)
                    .iterator() : Collections.emptyIterator();
        }

        IdFilter filter = extractIdFilter(scope, project);
        IntPredicate accessibleFileFilter = getAccessibleFileIdFilter(project);

        IntSet fileIds = processExceptions(indexId, null, scope, index -> {
            IntSet fileIdsInner = new IntOpenHashSet();
//                trace.totalKeysIndexed(keysCountApproximatelyIfPossible(index));
            index.getData(dataKey).forEach((id, value) -> {
                if (!accessibleFileFilter.test(id) ||
                    (filter != null && !filter.containsFileId(id))) {
                    return true;
                }
                fileIdsInner.add(id);
                return true;
            });
            return fileIdsInner;
        });

//            trace.lookupResultSize(fileIds != null ? fileIds.size() : 0);

        return createLazyFileIterator(fileIds, scope);
//        }
    }

    @Override
    public <K, V> boolean processValues(@NonNull final ID<K, V> indexId,
                                        @NonNull final K dataKey,
                                        @Nullable final VirtualFile inFile,
                                        @NonNull ValueProcessor<? super V> processor,
                                        @NonNull final GlobalSearchScope filter) {
        return processValues(indexId, dataKey, inFile, processor, filter, null);
    }

    @Override
    public <K, V> boolean processValues(@NonNull ID<K, V> indexId,
                                        @NonNull K dataKey,
                                        @Nullable VirtualFile inFile,
                                        @NonNull ValueProcessor<? super V> processor,
                                        @NonNull GlobalSearchScope filter,
                                        @Nullable IdFilter idFilter) {
        return inFile != null ? processValuesInOneFile(indexId,
                dataKey,
                inFile,
                filter,
                processor) : processValuesInScope(indexId,
                dataKey,
                false,
                filter,
                idFilter,
                processor);
    }

    @Override
    public <K, V> long getIndexModificationStamp(@NonNull ID<K, V> indexId,
                                                 @NonNull Project project) {
        waitUntilIndicesAreInitialized();
        UpdatableIndex<K, V, FileContent, ?> index = getIndex(indexId);
        ensureUpToDate(indexId, project, GlobalSearchScope.allScope(project));
        return index.getModificationStamp();
    }

    @Nullable
    private <K, V, R> R processExceptions(@NonNull final ID<K, V> indexId,
                                          @Nullable final VirtualFile restrictToFile,
                                          @NonNull final GlobalSearchScope filter,
                                          @NonNull ThrowableConvertor<? super UpdatableIndex<K, V
                                                  , FileContent, ?>, ? extends R,
                                                  StorageException> computable) {
        try {
            waitUntilIndicesAreInitialized();
            UpdatableIndex<K, V, FileContent, ?> index = getIndex(indexId);
            Project project = filter.getProject();
            //assert project != null : "GlobalSearchScope#getProject() should be not-null for all
            // index queries";
            if (!ensureUpToDate(indexId, project, filter, restrictToFile)) {
                return null;
            }
//      TRACE_OF_ENTRIES_LOOKUP.get()
//        .indexValidationFinished();

            index.getLock().readLock().lock();
            try {
                return computable.convert(index);
            } finally {
                index.getLock().readLock().unlock();
            }
        } catch (StorageException e) {
//      TRACE_OF_ENTRIES_LOOKUP.get().lookupFailed();
            scheduleRebuild(indexId, e);
        } catch (RuntimeException e) {
            final Throwable cause = getCauseToRebuildIndex(e);
            if (cause != null) {
                scheduleRebuild(indexId, cause);
            } else {
                throw e;
            }
        }
        return null;
    }

    protected <K, V> boolean processValuesInOneFile(@NonNull ID<K, V> indexId,
                                                    @NonNull K dataKey,
                                                    @NonNull VirtualFile restrictToFile,
                                                    @NonNull GlobalSearchScope scope,
                                                    @NonNull ValueProcessor<? super V> processor) {
        Project project = scope.getProject();
        if (!(restrictToFile instanceof VirtualFileWithId)) {
            return project == null ||
                   ModelBranch.getFileBranch(restrictToFile) == null ||
                   processInMemoryFileData(indexId, dataKey, project, restrictToFile, processor);
        }

        int restrictedFileId = getFileId(restrictToFile);

        if (!getAccessibleFileIdFilter(project).test(restrictedFileId)) {
            return true;
        }

        return processValueIterator(indexId, dataKey, restrictToFile, scope, valueIt -> {
            while (valueIt.hasNext()) {
                V value = valueIt.next();
                if (valueIt.getValueAssociationPredicate().test(restrictedFileId) &&
                    !processor.process(restrictToFile, value)) {
                    return false;
                }
                ProgressManager.checkCanceled();
            }
            return true;
        });
    }

    private <K, V> boolean processInMemoryFileData(ID<K, V> indexId,
                                                   K dataKey,
                                                   Project project,
                                                   VirtualFile file,
                                                   ValueProcessor<? super V> processor) {
        Map<K, V> data = getFileData(indexId, file, project);
        return !data.containsKey(dataKey) || processor.process(file, data.get(dataKey));
    }

    protected <K, V> boolean processValuesInScope(@NonNull ID<K, V> indexId,
                                                  @NonNull K dataKey,
                                                  boolean ensureValueProcessedOnce,
                                                  @NonNull GlobalSearchScope scope,
                                                  @Nullable IdFilter idFilter,
                                                  @NonNull ValueProcessor<? super V> processor) {
        Project project = scope.getProject();
        if (project != null && true) {
            getLogger().warn("Process values in scope not yet implemented");
//            !ModelBranchImpl.processModifiedFilesInScope(scope,
//                    file -> processInMemoryFileData(indexId, dataKey, project, file, processor)
//                    )) {
            return false;
        }

        IdFilter filter = idFilter != null ? idFilter : extractIdFilter(scope, project);
        IntPredicate accessibleFileFilter = getAccessibleFileIdFilter(project);

        return processValueIterator(indexId, dataKey, null, scope, valueIt -> {
            Collection<ModelBranch> branches = null;
            while (valueIt.hasNext()) {
                final V value = valueIt.next();
                for (final ValueContainer.IntIterator inputIdsIterator =
                     valueIt.getInputIdsIterator(); inputIdsIterator.hasNext(); ) {
                    final int id = inputIdsIterator.next();
                    if (!accessibleFileFilter.test(id) ||
                        (filter != null && !filter.containsFileId(id))) {
                        continue;
                    }
                    VirtualFile file = findFileById(id);
                    if (file != null) {
                        if (branches == null) {
                            branches = scope.getModelBranchesAffectingScope();
                        }
                        for (VirtualFile eachFile : filesInScopeWithBranches(scope,
                                file,
                                branches)) {
                            if (!processor.process(eachFile, value)) {
                                return false;
                            }
                            if (ensureValueProcessedOnce) {
                                ProgressManager.checkCanceled();
                                break; // continue with the next value
                            }
                        }
                    }

                    ProgressManager.checkCanceled();
                }
            }
            return true;
        });
    }

    private <K, V> boolean processValueIterator(@NonNull ID<K, V> indexId,
                                                @NonNull K dataKey,
                                                @Nullable VirtualFile restrictToFile,
                                                @NonNull GlobalSearchScope scope,
                                                @NonNull Processor<?
                                                        super InvertedIndexValueIterator<V>> valueProcessor) {
//    try (var trace = lookupEntriesStarted(indexId)) {
//      trace.keysWithAND(1)
//        .withProject(scope.getProject());
        //TODO RC: .scopeFiles( restrictToFile == null ? -1 : 1 )
        final ThrowableConvertor<UpdatableIndex<K, V, FileContent, ?>, Boolean, StorageException>
                convertor = index -> {
//        trace.totalKeysIndexed(keysCountApproximatelyIfPossible(index));
            InvertedIndexValueIterator<V> valuesIterator =
                    (InvertedIndexValueIterator<V>) index.getData(dataKey).getValueIterator();
            return valueProcessor.process(valuesIterator);
        };
        final Boolean result = processExceptions(indexId, restrictToFile, scope, convertor);
        return result == null || result;
//    }
    }

    @Override
    public <K, V> boolean processFilesContainingAllKeys(@NonNull ID<K, V> indexId,
                                                        @NonNull Collection<? extends K> dataKeys,
                                                        @NonNull GlobalSearchScope filter,
                                                        @Nullable Condition<? super V> valueChecker,
                                                        @NonNull Processor<? super VirtualFile> processor) {
        IdFilter filesSet = extractIdFilter(filter, filter.getProject());
        IntSet set = collectFileIdsContainingAllKeys(indexId,
                dataKeys,
                filter,
                valueChecker,
                filesSet,
                null);
        return set != null && processVirtualFiles(set, filter, processor);
    }

    @Override
    public <K, V> boolean processFilesContainingAnyKey(@NonNull ID<K, V> indexId,
                                                       @NonNull Collection<? extends K> dataKeys,
                                                       @NonNull GlobalSearchScope filter,
                                                       @Nullable IdFilter idFilter,
                                                       @Nullable Condition<? super V> valueChecker,
                                                       @NonNull Processor<? super VirtualFile> processor) {
        IdFilter idFilterAdjusted =
                idFilter != null ? idFilter : extractIdFilter(filter, filter.getProject());
        IntSet set = collectFileIdsContainingAnyKey(indexId,
                dataKeys,
                filter,
                valueChecker,
                idFilterAdjusted);
        return set != null && processVirtualFiles(set, filter, processor);
    }

    private boolean processFilesContainingAllKeysInPhysicalFiles(@NonNull Collection<?
            extends AllKeysQuery<?, ?>> queries,
                                                                 @NonNull GlobalSearchScope filter,
                                                                 Processor<? super VirtualFile> processor,
                                                                 IdFilter filesSet) {
        IntSet set = null;
        if (filter instanceof GlobalSearchScope.FilesScope) {
            VirtualFileEnumeration hint = VirtualFileEnumeration.extract(filter);
            set = new IntOpenHashSet();
            if (hint != null) {
                for (int i : hint.asArray()) {
                    set.add(i);
                }
            }
        }

        //noinspection rawtypes
        for (AllKeysQuery query : queries) {
            @SuppressWarnings("unchecked") IntSet queryResult = collectFileIdsContainingAllKeys(
                    query.getIndexId(),
                    query.getDataKeys(),
                    filter,
                    query.getValueChecker(),
                    filesSet,
                    set);
            if (queryResult == null) {
                return false;
            }
            if (queryResult.isEmpty()) {
                return true;
            }
            if (set == null) {
                set = new IntOpenHashSet();
                set.addAll(queryResult);
            } else {
                set = queryResult;
            }
        }
        if (set == null || !processVirtualFiles(set, filter, processor)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean processFilesContainingAllKeys(@NonNull Collection<? extends AllKeysQuery<?, ?>> queries,
                                                 @NonNull GlobalSearchScope filter,
                                                 @NonNull Processor<? super VirtualFile> processor) {
        Project project = filter.getProject();
        IdFilter filesSet = extractIdFilter(filter, filter.getProject());

        if (!processFilesContainingAllKeysInPhysicalFiles(queries, filter, processor, filesSet)) {
            return false;
        }

        if (project == null) {
            return true;
        }

        getLogger().warn("procesFilesContainingAllKeys not yet implemented");
//        for (AllKeysQuery<?, ?> query : queries) {
//            ID<?, ?> id = query.getIndexId();
//            Map<?, ?> data = getFileData(id, file, project);
//        }
        return false;
//        return ModelBranchImpl.processModifiedFilesInScope(filter, file -> {
//            for (AllKeysQuery<?, ?> query : queries) {
//                ID<?, ?> id = query.getIndexId();
//                Map<?, ?> data = getFileData(id, file, project);
//                if (!data.keySet().containsAll(query.getDataKeys())) {
//                    return true;
//                }
//            }
//            return processor.process(file);
//        });
    }

    @Override
    public <K, V> boolean getFilesWithKey(@NonNull final ID<K, V> indexId,
                                          @NonNull final Set<? extends K> dataKeys,
                                          @NonNull Processor<? super VirtualFile> processor,
                                          @NonNull GlobalSearchScope filter) {
        return processFilesContainingAllKeys(indexId, dataKeys, filter, null, processor);
    }


    @Override
    public <K> void scheduleRebuild(@NonNull final ID<K, ?> indexId, @NonNull final Throwable e) {
        requestRebuild(indexId, e);
    }

    /**
     * DO NOT CALL DIRECTLY IN CLIENT CODE
     * The method is internal to indexing engine end is called internally. The method is public due
     * to implementation details
     */
    @Override
    public <K> void ensureUpToDate(@NonNull final ID<K, ?> indexId,
                                   @Nullable Project project,
                                   @Nullable GlobalSearchScope filter) {
        waitUntilIndicesAreInitialized();
        ensureUpToDate(indexId, project, filter, null);
    }

    @Override
    public void iterateIndexableFiles(@NonNull ContentIterator processor,
                                      @NonNull Project project,
                                      @Nullable ProgressIndicator indicator) {
        List<IndexableFilesIterator> providers = getIndexableFilesProviders(project);
        IndexableFilesDeduplicateFilter indexableFilesDeduplicateFilter =
                IndexableFilesDeduplicateFilter.create();
        for (IndexableFilesIterator provider : providers) {
            if (indicator != null) {
                indicator.checkCanceled();
            }
            if (!provider.iterateFiles(project, processor, indexableFilesDeduplicateFilter)) {
                break;
            }
        }
    }

    /**
     * Returns providers of files to be indexed.
     */
    @NonNull
    public List<IndexableFilesIterator> getIndexableFilesProviders(@NonNull Project project) {
        List<String> allowedIteratorPatterns =
                StringUtil.split(System.getProperty("idea.test.files.allowed.iterators", ""), ";");
//    if (project instanceof LightEditCompatible) {
//      return Collections.emptyList();
//    }
        if (IndexableFilesIndex.isEnabled() && allowedIteratorPatterns.isEmpty()) {
            return IndexableFilesIndex.getInstance(project).getIndexingIterators();
        }
        List<IndexableFilesIterator> providers =
                IndexableFilesContributor.EP_NAME.getExtensionList()
                        .stream()
                        .flatMap(c -> ReadAction.compute(() -> c.getIndexableFiles(project)).stream())
                        .collect(Collectors.toList());
        if (!allowedIteratorPatterns.isEmpty()) {
            providers = ContainerUtil.filter(providers, p -> {
                return ContainerUtil.exists(allowedIteratorPatterns,
                        pattern -> p.getDebugName().contains(pattern));
            });
        }
        return providers;
    }

    @Nullable
    private <K, V> IntSet collectFileIdsContainingAllKeys(@NonNull ID<K, V> indexId,
                                                          @NonNull Collection<? extends K> dataKeys,
                                                          @NonNull GlobalSearchScope scope,
                                                          @Nullable Condition<? super V> valueChecker,
                                                          @Nullable IdFilter projectFilesFilter,
                                                          @Nullable IntSet restrictedIds) {
//    try (var trace = lookupEntriesStarted(indexId)) {
//      trace.keysWithAND(dataKeys.size())
//        .withProject(scope.getProject());

        IntPredicate accessibleFileFilter = getAccessibleFileIdFilter(scope.getProject());
        IntPredicate idChecker =
                id -> (projectFilesFilter == null || projectFilesFilter.containsFileId(id)) &&
                      accessibleFileFilter.test(id) &&
                      (restrictedIds == null || restrictedIds.contains(id));
        ThrowableConvertor<UpdatableIndex<K, V, FileContent, ?>, IntSet, StorageException>
                convertor = index -> {
//        trace.totalKeysIndexed(keysCountApproximatelyIfPossible(index));
            IndexDebugProperties.DEBUG_INDEX_ID.set(indexId);
            try {
                return InvertedIndexUtil.collectInputIdsContainingAllKeys(index,
                        dataKeys,
                        valueChecker,
                        idChecker);
            } finally {
                IndexDebugProperties.DEBUG_INDEX_ID.remove();
            }
        };

        final IntSet ids = processExceptions(indexId, null, scope, convertor);

//      trace.lookupResultSize(ids != null ? ids.size() : 0);
        return ids;
//    }
    }

    @Nullable
    private <K, V> IntSet collectFileIdsContainingAnyKey(@NonNull ID<K, V> indexId,
                                                         @NonNull Collection<? extends K> dataKeys,
                                                         @NonNull GlobalSearchScope filter,
                                                         @Nullable Condition<? super V> valueChecker,
                                                         @Nullable IdFilter projectFilesFilter) {
//        try (var trace = lookupEntriesStarted(indexId)) {
//            trace.keysWithOR(dataKeys.size());
        IntPredicate accessibleFileFilter = getAccessibleFileIdFilter(filter.getProject());
        IntPredicate idChecker =
                id -> (projectFilesFilter == null || projectFilesFilter.containsFileId(id)) &&
                      accessibleFileFilter.test(id);
        ThrowableConvertor<UpdatableIndex<K, V, FileContent, ?>, IntSet, StorageException>
                convertor = index -> {
//                trace.totalKeysIndexed(keysCountApproximatelyIfPossible(index));
            IndexDebugProperties.DEBUG_INDEX_ID.set(indexId);
            try {
                return InvertedIndexUtil.collectInputIdsContainingAnyKey(index,
                        dataKeys,
                        valueChecker,
                        idChecker);
            } finally {
                IndexDebugProperties.DEBUG_INDEX_ID.remove();
            }
        };

        final IntSet ids = processExceptions(indexId, null, filter, convertor);
//            trace.lookupResultSize(ids != null ? ids.size() : 0);
        return ids;
//        }
    }

    private boolean processVirtualFiles(@NonNull IntCollection ids,
                                        @NonNull GlobalSearchScope filter,
                                        @NonNull Processor<? super VirtualFile> processor) {
        // ensure predictable order because result might be cached by consumer
        IntList sortedIds = new IntArrayList();
        sortedIds.addAll(ids);
        sortedIds.sort(null);

        Collection<ModelBranch> branches = null;
        for (IntIterator iterator = sortedIds.iterator(); iterator.hasNext(); ) {
            ProgressManager.checkCanceled();
            int id = iterator.nextInt();
            VirtualFile file = findFileById(id);
            if (file != null) {
                if (branches == null) {
                    branches = filter.getModelBranchesAffectingScope();
                }
                for (VirtualFile fileInBranch : filesInScopeWithBranches(filter, file, branches)) {
                    boolean processNext = processor.process(fileInBranch);
                    ProgressManager.checkCanceled();
                    if (!processNext) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public @Nullable DumbModeAccessType getCurrentDumbModeAccessType() {
        DumbModeAccessType result = getCurrentDumbModeAccessType_NoDumbChecks();
        if (result != null) {

            getLogger().assertTrue(DumbService.isDumb(ProjectCoreUtil.theOnlyOpenProject()),
                    "getCurrentDumbModeAccessType may only be called during indexing");
        }
        return result;
    }

    @Nullable
    DumbModeAccessType getCurrentDumbModeAccessType_NoDumbChecks() {
        Stack<DumbModeAccessType> dumbModeAccessTypeStack = ourDumbModeAccessTypeStack.get();
        if (dumbModeAccessTypeStack.isEmpty()) {
            return null;
        }

        ApplicationManager.getApplication().assertReadAccessAllowed();
        ourIgnoranceGuard.prohibitResultCaching(dumbModeAccessTypeStack.get(0));
        return dumbModeAccessTypeStack.peek();
    }

    @Override
    public <T> Processor<? super T> inheritCurrentDumbAccessType(@NonNull Processor<? super T> processor) {
        Stack<DumbModeAccessType> stack = ourDumbModeAccessTypeStack.get();
        if (stack.isEmpty()) {
            return processor;
        }

        DumbModeAccessType access = stack.peek();
        return t -> ignoreDumbMode(access, () -> processor.process(t));
    }

    //    @ApiStatus.Experimental
    @Override
    public <T, E extends Throwable> T ignoreDumbMode(@NonNull DumbModeAccessType dumbModeAccessType,
                                                     @NonNull ThrowableComputable<T, E> computable) throws E {
        Application app = ApplicationManager.getApplication();
        app.assertReadAccessAllowed();
        if (FileBasedIndex.isIndexAccessDuringDumbModeEnabled()) {
            Stack<DumbModeAccessType> dumbModeAccessTypeStack = ourDumbModeAccessTypeStack.get();
            boolean preventCaching = dumbModeAccessTypeStack.empty();
            dumbModeAccessTypeStack.push(dumbModeAccessType);
            Disposable disposable = Disposer.newDisposable();
//            if (app.isWriteIntentLockAcquired()) {
//                app.getMessageBus()
//                        .connect(disposable)
//                        .subscribe(PsiModificationTracker.TOPIC,
//                                () -> RecursionManager.dropCurrentMemoizationCache());
//            }
            try {
                return preventCaching ? ourIgnoranceGuard.computePreventingRecursion(
                        dumbModeAccessType,
                        false,
                        computable) : computable.compute();
            } finally {
                Disposer.dispose(disposable);
                DumbModeAccessType type = dumbModeAccessTypeStack.pop();
                assert dumbModeAccessType == type;
            }
        } else {
            return computable.compute();
        }
    }

    @Nullable
    public abstract VirtualFile findFileById(int id);

    @NonNull
    public abstract Logger getLogger();

    @NonNull
    public static List<VirtualFile> filesInScopeWithBranches(@NonNull GlobalSearchScope scope,
                                                             @NonNull VirtualFile file,
                                                             @NonNull Collection<ModelBranch> branchesAffectingScope) {
        List<VirtualFile> filesInScope;
        filesInScope = new SmartList<>();
        if (scope.contains(file)) {
            filesInScope.add(file);
        }
//        ProgressManager.checkCanceled();
//        for (ModelBranch branch : branchesAffectingScope) {
//            VirtualFile copy = branch.findFileCopy(file);
//            if (!((ModelBranchImpl) branch).hasModifications(copy) && scope.contains(copy)) {
//                filesInScope.add(copy);
//            }
//            ProgressManager.checkCanceled();
//        }
        return filesInScope;
    }

    @Nullable
    public static Throwable getCauseToRebuildIndex(@NonNull RuntimeException e) {
        if (ApplicationManager.getApplication().isUnitTestMode()) {
            // avoid rebuilding index in tests since we do it synchronously in requestRebuild,
            // and we can have readAction at hand
            return null;
        }
        if (e instanceof ProcessCanceledException) {
            return null;
        }
        if (e instanceof MapReduceIndexMappingException) {
            if (e.getCause() instanceof SnapshotInputMappingException) {
                // IDEA-258515: corrupted snapshot index storage must be rebuilt.
                return e.getCause();
            }
            // If exception has happened on input mapping (DataIndexer.map),
            // it is handled as the indexer exception and must not lead to index rebuild.
            return null;
        }
        if (e instanceof IndexOutOfBoundsException) {
            return e; // something wrong with direct byte buffer
        }
        Throwable cause = e.getCause();
        if (cause instanceof StorageException ||
            cause instanceof IOException ||
            cause instanceof IllegalArgumentException) {
            return cause;
        }
        return null;
    }

    public boolean isTooLarge(@NotNull VirtualFile file) {
        return isTooLarge(file, file.getLength(), Collections.emptySet());
    }

    public static boolean isTooLarge(@NonNull VirtualFile file,
                                     Long contentSize,
                                     @NonNull Set<FileType> noLimitFileTypes) {
        if (SingleRootFileViewProvider.isTooLargeForIntelligence(file, contentSize)) {
            return !noLimitFileTypes.contains(file.getFileType()) ||
                   SingleRootFileViewProvider.isTooLargeForContentLoading(file, contentSize);
        }
        return false;
    }

    public static boolean acceptsInput(@NonNull InputFilter filter,
                                       @NonNull IndexedFile indexedFile) {
        if (filter instanceof ProjectSpecificInputFilter) {
            if (indexedFile.getProject() == null) {
                Project project = ProjectCoreUtil.theOnlyOpenProject();
                ((IndexedFileImpl) indexedFile).setProject(project);
            }
            return ((ProjectSpecificInputFilter) filter).acceptInput(indexedFile);
        }
        return filter.acceptInput(indexedFile.getFile());
    }

    @NonNull
    public static InputFilter composeInputFilter(@NonNull InputFilter filter,
                                                 @NonNull BiPredicate<? super VirtualFile, ?
                                                         super Project> condition) {
        return (ProjectSpecificInputFilter) file -> {
            boolean doesMainFilterAccept =
                    filter instanceof ProjectSpecificInputFilter ?
                            ((ProjectSpecificInputFilter) filter).acceptInput(
                            file) : filter.acceptInput(file.getFile());
            return doesMainFilterAccept && condition.test(file.getFile(), file.getProject());
        };
    }

    public void runCleanupAction(@NonNull Runnable cleanupAction) {
    }

    public static <T, E extends Throwable> T disableUpToDateCheckIn(@NonNull ThrowableComputable<T, E> runnable) throws E {
        return IndexUpToDateCheckIn.disableUpToDateCheckIn(runnable);
    }

    static boolean belongsToScope(@Nullable VirtualFile file,
                                  @Nullable VirtualFile restrictedTo,
                                  @Nullable GlobalSearchScope filter) {
        if (!(file instanceof VirtualFileWithId) || !file.isValid()) {
            return false;
        }

        return (restrictedTo == null || Comparing.equal(file, restrictedTo)) &&
               (filter == null || restrictedTo != null || filter.accept(file));
    }

    @NonNull
    public static Iterator<VirtualFile> createLazyFileIterator(@Nullable IntSet result,
                                                               @NonNull GlobalSearchScope scope) {
        Set<VirtualFile> integerSet;
        if (result == null) {
            integerSet = Collections.emptySet();
        } else {
            integerSet = new HashSet<>(result.size());

            for (Integer integer : result) {
                VirtualFile fileById = VirtualFileManager.getInstance().findFileById(integer);
                integerSet.add(fileById);
            }

        }
        CompactVirtualFileSet fileSet =
                new CompactVirtualFileSet(result == null ? Collections.emptySet() : integerSet);
        fileSet.freeze();
        return fileSet.stream().filter(scope::contains).iterator();
    }

    @SuppressWarnings("unchecked")
    public static @Nullable Iterator<VirtualFile> extractSingleFileOrEmpty(@Nullable GlobalSearchScope scope) {
        if (scope == null) {
            return null;
        }

        VirtualFileEnumeration enumeration = VirtualFileEnumeration.extract(scope);
        Iterable<VirtualFile> scopeAsFileIterable = enumeration !=
                                                    null ? enumeration.getFilesIfCollection() :
                scope instanceof Iterable<?> ? (Iterable<VirtualFile>) scope : null;
        if (scopeAsFileIterable == null) {
            return null;
        }

        VirtualFile result = null;
        boolean isFirst = true;

        for (VirtualFile file : scopeAsFileIterable) {
            if (!isFirst) {
                return null;
            }
            result = file;
            isFirst = false;
        }

        return isFirst ? Collections.emptyIterator() : result instanceof VirtualFileWithId ?
                Collections.singletonList(
                result).iterator() : null;
    }

    public static @NonNull Iterable<VirtualFile> toFileIterable(int[] fileIds) {
        if (fileIds.length == 0) {
            return Collections.emptyList();
        }
        return () -> new Iterator<VirtualFile>() {
            int myId;
            VirtualFile myNext;

            @Override
            public boolean hasNext() {
                while (myNext == null && myId < fileIds.length) {
                    myNext = VirtualFileManager.getInstance().findFileById(fileIds[myId++]);
                }
                return myNext != null;
            }

            @Override
            public VirtualFile next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                VirtualFile next = myNext;
                myNext = null;
                return next;
            }
        };
    }
}