package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.model.ModelBranch;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileWithId;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValue;
import org.jetbrains.kotlin.com.intellij.psi.util.CachedValueProvider;
import org.jetbrains.kotlin.com.intellij.util.CachedValueImpl;
import org.jetbrains.kotlin.com.intellij.util.ConcurrencyUtil;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.Processors;
import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentFactoryMap;
import org.jetbrains.kotlin.com.intellij.util.containers.FactoryMap;
import org.jetbrains.kotlin.com.intellij.util.indexing.DataIndexer;
import org.jetbrains.kotlin.com.intellij.util.indexing.DumbModeAccessType;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileIdStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IdFilter;
import org.jetbrains.kotlin.com.intellij.util.indexing.StorageException;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.ValueContainer;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.AbstractUpdateData;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.KeyValueUpdateProcessor;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.RemovedKeyProcessor;
import org.jetbrains.kotlin.com.intellij.util.io.DataExternalizer;
import org.jetbrains.kotlin.com.intellij.util.io.KeyDescriptor;
import org.jetbrains.kotlin.com.intellij.util.io.VoidDataExternalizer;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntIterator;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSet;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.ints.IntSets;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

public abstract class StubIndexEx extends StubIndex {

    static void initExtensions() {
        // initialize stub index keys
        for (StubIndexExtension<?, ?> extension : StubIndexExtension.EP_NAME.getExtensionList()) {
            extension.getKey();
        }
    }

    private final Map<StubIndexKey<?, ?>, CachedValue<Map<KeyAndFileId<?>, StubIdList>>>
            myCachedStubIds = FactoryMap.create(k -> {
        UpdatableIndex<Integer, SerializedStubTree, FileContent, ?> index = getStubUpdatingIndex();
        ModificationTracker tracker = index::getModificationStamp;
        return new CachedValueImpl<>(() -> new CachedValueProvider.Result<>(new ConcurrentHashMap<>(), tracker));
    });

    private final StubProcessingHelper myStubProcessingHelper = new StubProcessingHelper();

    protected abstract void initializeStubIndexes();

    public abstract void initializationFailed(@NonNull Throwable error);

    public <K> void updateIndex(@NonNull StubIndexKey<K, ?> stubIndexKey,
                                int fileId,
                                @NonNull Set<? extends K> oldKeys,
                                @NonNull Set<? extends K> newKeys) {
        ProgressManager.getInstance().executeNonCancelableSection(() -> {
            try {
                if (FileBasedIndexEx.DO_TRACE_STUB_INDEX_UPDATE) {
                    getLogger().info("stub index '" + stubIndexKey + "' update: " + fileId +
                                     " old = " + Arrays.toString(oldKeys.toArray()) +
                                     " new  = " + Arrays.toString(newKeys.toArray()) +
                                     " updated_id = " + System.identityHashCode(newKeys));
                }
                final UpdatableIndex<K, Void, FileContent, ?> index = getIndex(stubIndexKey);
                if (index == null) return;
                index.updateWithMap(new AbstractUpdateData<K, Void>(fileId) {
                    @Override
                    protected boolean iterateKeys(@NonNull KeyValueUpdateProcessor<? super K, ? super Void> addProcessor,
                                                  @NonNull KeyValueUpdateProcessor<? super K, ? super Void> updateProcessor,
                                                  @NonNull RemovedKeyProcessor<? super K> removeProcessor) throws StorageException {
                        boolean modified = false;

                        for (K oldKey : oldKeys) {
                            if (!newKeys.contains(oldKey)) {
                                removeProcessor.process(oldKey, fileId);
                                if (!modified) modified = true;
                            }
                        }

                        for (K oldKey : newKeys) {
                            if (!oldKeys.contains(oldKey)) {
                                addProcessor.process(oldKey, null, fileId);
                                if (!modified) modified = true;
                            }
                        }

                        if (FileBasedIndexEx.DO_TRACE_STUB_INDEX_UPDATE) {
                            getLogger().info("keys iteration finished updated_id = " + System.identityHashCode(newKeys) + "; modified = " + modified);
                        }

                        return modified;
                    }
                });
            }
            catch (StorageException e) {
                getLogger().info(e);
                forceRebuild(e);
            }
        });
    }

    @NonNull
    public abstract Logger getLogger();

    @Override
    public <Key, Psi extends PsiElement> boolean processElements(@NonNull StubIndexKey<Key, Psi> indexKey,
                                                                 @NonNull Key key,
                                                                 @NonNull Project project,
                                                                 @Nullable GlobalSearchScope scope,
                                                                 @Nullable IdFilter idFilter,
                                                                 @NonNull Class<Psi> requiredClass,
                                                                 @NonNull Processor<? super Psi> processor) {
//        var trace = lookupStubEntriesStarted(indexKey)
//                .withProject(project);

        try {
            boolean dumb = DumbService.isDumb(project);
            if (dumb) {
//                if (project instanceof LightEditCompatible) return false;
                DumbModeAccessType accessType = FileBasedIndex.getInstance().getCurrentDumbModeAccessType();
                if (accessType == DumbModeAccessType.RAW_INDEX_DATA_ACCEPTABLE) {
                    throw new AssertionError("raw index data access is not available for StubIndex");
                }
            }
            Predicate<? super Psi>
                    keyFilter = StubIndexKeyDescriptorCache.INSTANCE.getKeyPsiMatcher(indexKey, key);
            PairProcessor<VirtualFile, StubIdList> stubProcessor = (file, list) -> myStubProcessingHelper.processStubsInFile(
                    project, file, list, keyFilter == null ? processor : o -> !keyFilter.test(o) || processor.process(o), scope, requiredClass);

//            if (!ModelBranchImpl.processModifiedFilesInScope(scope != null ? scope : GlobalSearchScope.everythingScope(project),
//                    file -> processInMemoryStubs(indexKey, key, project, stubProcessor, file))) {
//                return false;
//            }

            Iterator<VirtualFile> singleFileInScope = FileBasedIndexEx.extractSingleFileOrEmpty(scope);
            Iterator<VirtualFile> fileStream;
            boolean shouldHaveKeys;

            if (singleFileInScope != null) {
                if (!(singleFileInScope.hasNext())) return true;
                FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, project, scope);
                fileStream = singleFileInScope;
//                trace.lookupResultSize(1);
                shouldHaveKeys = false;
            }
            else {
                IntSet fileIds = getContainingIds(indexKey, key, project, idFilter, scope);
                if (fileIds == null) {
//                    trace.lookupResultSize(0);
                    return true;
                }
                else {
//                    trace.lookupResultSize(fileIds.size());
                }
                IntPredicate accessibleFileFilter = ((FileBasedIndexEx)FileBasedIndex.getInstance()).getAccessibleFileIdFilter(project);

                // already ensured up-to-date in getContainingIds() method
                IntIterator idIterator = fileIds.iterator();
                fileStream = StubIndexImplUtil.mapIdIterator(idIterator, accessibleFileFilter);
                shouldHaveKeys = true;
            }

//            trace.stubTreesDeserializingStarted();

            try {
                Collection<ModelBranch> branches = null;
                while (fileStream.hasNext()) {
                    VirtualFile file = fileStream.next();
                    assert file != null;
                    List<VirtualFile> filesInScope;
                    if (scope != null) {
                        if (branches == null) branches = scope.getModelBranchesAffectingScope();
                        filesInScope = FileBasedIndexEx.filesInScopeWithBranches(scope, file, branches);
                    }
                    else {
                        filesInScope = Collections.singletonList(file);
                    }
                    if (filesInScope.isEmpty()) {
                        continue;
                    }

                    int id = ((VirtualFileWithId) file).getId();
                    StubIdList list = myCachedStubIds.get(indexKey).getValue().computeIfAbsent(new KeyAndFileId<>(key, id), __ ->
                            myStubProcessingHelper.retrieveStubIdList(indexKey, key, file, project, shouldHaveKeys)
                    );
                    if (list == null) {
                        // stub index inconsistency
                        continue;
                    }
                    for (VirtualFile eachFile : filesInScope) {
                        if (!stubProcessor.process(eachFile, list)) {
                            return false;
                        }
                    }
                }
            }
            catch (RuntimeException e) {
//                trace.lookupFailed();
                final Throwable cause = FileBasedIndexEx.getCauseToRebuildIndex(e);
                if (cause != null) {
                    forceRebuild(cause);
                }
                else {
                    throw e;
                }
            }
            finally {
                wipeProblematicFileIdsForParticularKeyAndStubIndex(indexKey, key);
            }
            return true;
        }
        catch (Throwable t) {
//            trace.lookupFailed();
            throw t;
        }
        finally {
            //Not using try-with-resources because in case of exceptions are thrown, .close() needs to be called _after_ catch,
            //  so .lookupFailed() is invoked on a not-yet-closed trace -- but TWR does the opposite: first close resources, then
            //  do all catch/finally blocks
//            trace.close();
        }
    }

    private static <Key, Psi extends PsiElement> boolean processInMemoryStubs(StubIndexKey<Key, Psi> indexKey,
                                                                              Key key,
                                                                              Project project,
                                                                              PairProcessor<? super VirtualFile, ? super StubIdList> stubProcessor,
                                                                              VirtualFile file) {
        Map<Integer, SerializedStubTree> data = FileBasedIndex.getInstance().getFileData(StubUpdatingIndex.INDEX_ID, file, project);
        if (data.size() == 1) {
            try {
                StubIdList list = data.values().iterator().next().restoreIndexedStubs(indexKey, key);
                if (list != null) {
                    return stubProcessor.process(file, list);
                }
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    protected abstract <Key> UpdatableIndex<Key, Void, FileContent, ?> getIndex(@NonNull StubIndexKey<Key, ?> indexKey);

    // Self repair for IDEA-181227, caused by (yet) unknown file event processing problem in indices
    // FileBasedIndex.requestReindex doesn't handle the situation properly because update requires old data that was lost
    private <Key> void wipeProblematicFileIdsForParticularKeyAndStubIndex(@NonNull StubIndexKey<Key, ?> indexKey,
                                                                          @NonNull Key key) {
        Set<VirtualFile> filesWithProblems = myStubProcessingHelper.takeAccumulatedFilesWithIndexProblems();

        if (filesWithProblems != null) {
            getLogger().info("data for " + indexKey.getName() + " will be wiped for a some files because of internal stub processing error");
            ((FileBasedIndexEx)FileBasedIndex.getInstance()).runCleanupAction(() -> {
                Lock writeLock = getIndex(indexKey).getLock().writeLock();
                boolean locked = writeLock.tryLock();
                if (!locked) return; // nested indices invocation, can not cleanup without deadlock
                try {
                    for (VirtualFile file : filesWithProblems) {
                        updateIndex(indexKey,
                                FileBasedIndex.getFileId(file),
                                Collections.singleton(key),
                                Collections.emptySet());
                    }
                }
                finally {
                    writeLock.unlock();
                }
            });
        }
    }

    @Override
    public @NonNull <K> Collection<K> getAllKeys(@SuppressWarnings("BoundedWildcard") @NonNull StubIndexKey<K, ?> indexKey,
                                                 @NonNull Project project) {
        Set<K> allKeys = new HashSet<>();
        processAllKeys(indexKey, project, Processors.cancelableCollectProcessor(allKeys));
        return allKeys;
    }

    @Override
    public <K> boolean processAllKeys(@NonNull StubIndexKey<K, ?> indexKey,
                                      @NonNull Processor<? super K> processor,
                                      @NonNull GlobalSearchScope scope,
                                      @Nullable IdFilter idFilter) {
        final UpdatableIndex<K, Void, FileContent, ?> index = getIndex(indexKey); // wait for initialization to finish
        FileBasedIndexEx fileBasedIndexEx = (FileBasedIndexEx)FileBasedIndex.getInstance();
        if (index == null ||
            !fileBasedIndexEx.ensureUpToDate(StubUpdatingIndex.INDEX_ID, scope.getProject(), scope, null)) {
            return true;
        }

        if (idFilter == null) {
            idFilter = fileBasedIndexEx.extractIdFilter(scope, scope.getProject());
        }

        try {
            @Nullable IdFilter finalIdFilter = idFilter;
            return FileBasedIndexEx.disableUpToDateCheckIn(() -> index.processAllKeys(processor, scope, finalIdFilter));
        }
        catch (StorageException e) {
            forceRebuild(e);
        }
        catch (RuntimeException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException || cause instanceof StorageException) {
                forceRebuild(e);
            }
            throw e;
        }
        return true;
    }

    @Override
    public @NonNull <Key> Iterator<VirtualFile> getContainingFilesIterator(@NonNull StubIndexKey<Key, ?> indexKey,
                                                                           @NonNull Key dataKey,
                                                                           @NonNull Project project,
                                                                           @NonNull GlobalSearchScope scope) {
        IntSet result = getContainingIds(indexKey, dataKey, project, null, scope);
        return FileBasedIndexEx.createLazyFileIterator(result, scope);
    }

    @Override
    public <Key> int getMaxContainingFileCount(@NonNull StubIndexKey<Key, ?> indexKey,
                                               @NonNull Key dataKey,
                                               @NonNull Project project,
                                               @NonNull GlobalSearchScope scope) {
        IntSet result = getContainingIds(indexKey, dataKey, project, null, scope);
        return result == null ? 0 : result.size();
    }

    /**
     * @return set of fileId of files containing lookup key (dataKey)
     */
    private @Nullable <Key> IntSet getContainingIds(@NonNull StubIndexKey<Key, ?> indexKey,
                                                    @NonNull Key dataKey,
                                                    final @NonNull Project project,
                                                    @Nullable IdFilter idFilter,
                                                    final @Nullable GlobalSearchScope scope) {
//        var trace = TRACE_OF_STUB_ENTRIES_LOOKUP.get();
        final FileBasedIndexEx fileBasedIndex = (FileBasedIndexEx)FileBasedIndex.getInstance();
        ID<Integer, SerializedStubTree> stubUpdatingIndexId = StubUpdatingIndex.INDEX_ID;
        final UpdatableIndex<Key, Void, FileContent, ?> index = getIndex(indexKey);   // wait for initialization to finish
        if (index == null || !fileBasedIndex.ensureUpToDate(stubUpdatingIndexId, project, scope, null)) return null;

//        trace.indexValidationFinished();

        IdFilter finalIdFilter = idFilter != null
                ? idFilter
                : ((FileBasedIndexEx)FileBasedIndex.getInstance()).extractIdFilter(scope, project);

        UpdatableIndex<Integer, SerializedStubTree, FileContent, ?> stubUpdatingIndex = fileBasedIndex.getIndex(stubUpdatingIndexId);

        try {
            final IntSet[] result = {null};
            // workaround duplicates keys
            ValueContainer.ContainerAction<Void> action = (id, value) -> {
                if (finalIdFilter == null || finalIdFilter.containsFileId(id)) {
                    if (result[0] == null) {
                        result[0] = new IntOpenHashSet();
                    }
                    result[0].add(id);
                }
                return true;
            };
//            trace.totalKeysIndexed(MeasurableIndexStore.keysCountApproximatelyIfPossible(index));
            // disable up-to-date check to avoid locks on attempt to acquire index write lock while holding at the same time the readLock for this index
            FileBasedIndexEx.disableUpToDateCheckIn(() -> {
                Lock lock = stubUpdatingIndex.getLock().readLock();
                lock.lock();
                try {
                 return index.getData(dataKey).forEach(action);
                } finally {
                    lock.unlock();
                }
            });
            return result[0] == null ? IntSets.EMPTY_SET : result[0];
        }
        catch (StorageException e) {
//            trace.lookupFailed();
            forceRebuild(e);
        }
        catch (RuntimeException e) {
//            trace.lookupFailed();
            final Throwable cause = FileBasedIndexEx.getCauseToRebuildIndex(e);
            if (cause != null) {
                forceRebuild(cause);
            }
            else {
                throw e;
            }
        }

        return null;
    }

    protected void clearState() {
        StubIndexKeyDescriptorCache.INSTANCE.clear();
        ((SerializationManagerImpl)SerializationManagerEx.getInstanceEx()).dropSerializerData();
        myCachedStubIds.clear();
    }

    void setDataBufferingEnabled(final boolean enabled) { }

    void cleanupMemoryStorage() { }

    public static @NonNull <K> FileBasedIndexExtension<K, Void> wrapStubIndexExtension(StubIndexExtension<K, ?> extension) {
        return new FileBasedIndexExtension<K, Void>() {
            @Override
            public @NonNull ID<K, Void> getName() {
                @SuppressWarnings("unchecked") ID<K, Void> key = (ID<K, Void>)extension.getKey();
                return key;
            }

            @Override
            public @NonNull FileBasedIndex.InputFilter getInputFilter() {
                return f -> {
                    throw new UnsupportedOperationException();
                };
            }

            @Override
            public boolean dependsOnFileContent() {
                return true;
            }

            @Override
            public boolean needsForwardIndexWhenSharing() {
                return false;
            }

            @Override
            public @NonNull DataIndexer<K, Void, FileContent> getIndexer() {
                return i -> {
                    throw new AssertionError();
                };
            }

            @Override
            public @NonNull KeyDescriptor<K> getKeyDescriptor() {
                return extension.getKeyDescriptor();
            }

            @Override
            public @NonNull DataExternalizer<Void> getValueExternalizer() {
                return VoidDataExternalizer.INSTANCE;
            }

            @Override
            public int getVersion() {
                return extension.getVersion();
            }

            @Override
            public boolean traceKeyHashToVirtualFileMapping() {
                return extension instanceof StringStubIndexExtension && ((StringStubIndexExtension<?>)extension).traceKeyHashToVirtualFileMapping();
            }
        };
    }

    static UpdatableIndex<Integer, SerializedStubTree, FileContent, ?> getStubUpdatingIndex() {
        return ((FileBasedIndexEx)FileBasedIndex.getInstance()).getIndex(StubUpdatingIndex.INDEX_ID);
    }

    private static final class KeyAndFileId<K> {
        @NonNull
        private final K key;
        private final int fileId;

        private KeyAndFileId(@NonNull K key, int fileId) {
            this.key = key;
            this.fileId = fileId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyAndFileId<?> key1 = (KeyAndFileId<?>)o;
            return fileId == key1.fileId && Objects.equals(key, key1.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, fileId);
        }
    }

    public boolean areAllProblemsProcessedInTheCurrentThread() {
        return myStubProcessingHelper.areAllProblemsProcessedInTheCurrentThread();
    }

    public void cleanCaches() {
        myCachedStubIds.clear();
    }

    public interface FileUpdateProcessor {
        void processUpdate(@NonNull VirtualFile file);
        default void endUpdatesBatch() {}
    }
    abstract public @NonNull FileUpdateProcessor getPerFileElementTypeModificationTrackerUpdateProcessor();
}
