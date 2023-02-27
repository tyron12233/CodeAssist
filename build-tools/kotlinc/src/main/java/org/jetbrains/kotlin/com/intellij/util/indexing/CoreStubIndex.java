package org.jetbrains.kotlin.com.intellij.util.indexing;

import static org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil.delete;
import static org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil.findSequentNonexistentFile;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.AppUIExecutor;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.stubs.PerFileElementTypeStubModificationTracker;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIdList;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexEx;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexExtension;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndexKey;
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubUpdatingIndex;
import org.jetbrains.kotlin.com.intellij.psi.tree.StubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.ThrowableRunnable;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexEx;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndexInfrastructureExtension;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexDataInitializer;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexInfrastructure;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexVersion;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexVersionRegistrationSink;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.IndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.MapInputDataDiffBuilder;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.TransientFileContentIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.impl.storage.VfsAwareMapIndexStorage;
import org.jetbrains.kotlin.com.intellij.util.indexing.storage.VfsAwareIndexStorageLayout;
import org.jetbrains.kotlin.com.intellij.util.io.IOUtil;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;

/**
 * A basic implementation of StubIndex
 */
public class CoreStubIndex extends StubIndexEx {

    public enum PerFileElementTypeStubChangeTrackingSource {
        Disabled,
        ChangedFilesCollector
    }

    public static final PerFileElementTypeStubChangeTrackingSource PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE;
    static {
        int sourceId = SystemProperties.getIntProperty("stub.index.per.file.element.type.stub.change.tracking.source", 1);
        PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE = PerFileElementTypeStubChangeTrackingSource.values()[sourceId];
    }

    private static final Logger LOG = Logger.getInstance(CoreStubIndex.class);
    private final AtomicBoolean myForcedClean = new AtomicBoolean(false);

    private static final class AsyncState {
        private final Map<StubIndexKey<?, ?>, UpdatableIndex<?, Void, FileContent, ?>> myIndices =
                CollectionFactory.createSmallMemoryFootprintMap();
    }

    private volatile AsyncState myState;
    private volatile CompletableFuture<AsyncState> myStateFuture;
    private volatile boolean myInitialized;

    private final @NotNull PerFileElementTypeStubModificationTracker
            myPerFileElementTypeStubModificationTracker;
    public CoreStubIndex() {
        myPerFileElementTypeStubModificationTracker = new PerFileElementTypeStubModificationTracker();
    }

    private AsyncState getAsyncState() {
        AsyncState state = myState; // memory barrier
        if (state == null) {
            if (myStateFuture == null) {
                ((CoreFileBasedIndex) FileBasedIndex.getInstance()).waitUntilIndicesAreInitialized();
            }
            if (ProgressManager.getInstance().isInNonCancelableSection()) {
                try {
                    state = myStateFuture.get();
                } catch (Exception e) {
                    Logger.getInstance(CoreFileBasedIndex.class).error(e);
                }
            } else {
                CompletableFuture<AsyncState> future = myStateFuture;
                if (future == null) {
                    throw new AlreadyDisposedException("Stub Index is already disposed");
                }
                state = ProgressIndicatorUtils.awaitWithCheckCanceled(future);
            }
            myState = state;
        }
        return state;
    }

    @Override
    public void initializeStubIndexes() {
        assert !myInitialized;

        // might be called on the same thread twice if initialization has been failed
        if (myStateFuture == null) {
            FileBasedIndex.getInstance();

            myStateFuture = new CompletableFuture<>();
            IndexDataInitializer.submitGenesisTask(new StubIndexInitialization());
        }
    }

    @Override
    public void forceRebuild(@NonNull Throwable e) {
        FileBasedIndex.getInstance().scheduleRebuild(StubUpdatingIndex.INDEX_ID, e);
    }

    @NonNull
    @Override
    public ModificationTracker getPerFileElementTypeModificationTracker(@NonNull StubFileElementType<?> fileElementType) {
        return () -> {
            if (PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE == PerFileElementTypeStubChangeTrackingSource.ChangedFilesCollector) {
                ReadAction.compute(() -> {
                    ((CoreFileBasedIndex)FileBasedIndex.getInstance()).getChangedFilesCollector().processFilesToUpdateInReadAction();
                    return Unit.INSTANCE;
                });
            }
            return myPerFileElementTypeStubModificationTracker.getModificationStamp(fileElementType);
        };
    }

    @NonNull
    @Override
    public ModificationTracker getStubIndexModificationTracker(@NonNull Project project) {
        return () -> FileBasedIndex.getInstance().getIndexModificationStamp(StubUpdatingIndex.INDEX_ID, project);
    }


    @Override
    public void initializationFailed(@NonNull Throwable error) {

    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    private static <K> void registerIndexer(final @NotNull StubIndexExtension<K, ?> extension, final boolean forceClean,
                                            @NotNull AsyncState state, @NotNull IndexVersionRegistrationSink registrationResultSink) throws IOException {
        final StubIndexKey<K, ?> indexKey = extension.getKey();
        final int version = extension.getVersion();
        FileBasedIndexExtension<K, Void> wrappedExtension = wrapStubIndexExtension(extension);

        Path indexRootDir = IndexInfrastructure.getIndexRootDir(indexKey);
        IndexVersion.IndexVersionDiff versionDiff = forceClean
                ? new IndexVersion.IndexVersionDiff.InitialBuild(version)
                : IndexVersion.versionDiffers(indexKey, version);

        registrationResultSink.setIndexVersionDiff(indexKey, versionDiff);

        if (versionDiff != IndexVersion.IndexVersionDiff.UP_TO_DATE) {
            deleteWithRenamingIfExists(indexRootDir);
            IndexVersion.rewriteVersion(indexKey, version);

            try {
                for (FileBasedIndexInfrastructureExtension ex : FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
                    ex.onStubIndexVersionChanged(indexKey);
                }
            }
            catch (Exception e) {
                LOG.error(e);
            }
        }

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                UpdatableIndex<K, Void, FileContent, ?> index =
                        TransientFileContentIndex.createIndex(wrappedExtension, new StubIndexStorageLayout<>(wrappedExtension, indexKey));

                for (FileBasedIndexInfrastructureExtension infrastructureExtension : FileBasedIndexInfrastructureExtension.EP_NAME.getExtensionList()) {
                    UpdatableIndex<K, Void, FileContent, ?> intermediateIndex = infrastructureExtension.combineIndex(wrappedExtension, index);
                    if (intermediateIndex != null) {
                        index = intermediateIndex;
                    }
                }

                synchronized (state) {
                    state.myIndices.put(indexKey, index);
                }
                break;
            }
            catch (IOException e) {
                registrationResultSink.setIndexVersionDiff(indexKey, new IndexVersion.IndexVersionDiff.CorruptedRebuild(version));
                LOG.error("Failed to instantiate index: " + indexKey + " " + version);
//                onExceptionInstantiatingIndex(indexKey, version, indexRootDir, e);
            }
            catch (RuntimeException e) {
                Throwable cause = FileBasedIndexEx.getCauseToRebuildIndex(e);
                if (cause == null) {
                    throw e;
                }
                LOG.error("Failed to instantiate index: " + indexKey + " " + version);
//                onExceptionInstantiatingIndex(indexKey, version, indexRootDir, e);
            }
        }
    }

    void setDataBufferingEnabled(boolean enabled) {
        AsyncState state = ProgressManager.getInstance().computeInNonCancelableSection(this::getAsyncState);
        for (UpdatableIndex<?, ?, ?, ?> index : state.myIndices.values()) {
            index.setBufferingEnabled(enabled);
        }
    }

    @SuppressWarnings("unchecked")
    <K> void removeTransientDataForFile(@NotNull StubIndexKey<K, ?> key, int inputId, Map<K, StubIdList> keys) {
        UpdatableIndex<Object, Void, FileContent, ?> index = (UpdatableIndex)getIndex(key);
        index.removeTransientDataForKeys(inputId, new MapInputDataDiffBuilder(inputId, keys));
    }


    public long getIndexModificationStamp(@NotNull StubIndexKey<?, ?> indexId, @NotNull Project project) {
        UpdatableIndex<?, Void, FileContent, ?> index = getAsyncState().myIndices.get(indexId);
        if (index != null) {
            FileBasedIndex.getInstance().ensureUpToDate(StubUpdatingIndex.INDEX_ID, project, GlobalSearchScope.allScope(project));
            return index.getModificationStamp();
        }
        return -1;
    }

    /**
     * @implNote obtaining modification stamps might be expensive due to execution of StubIndex update on each invocation
     */
    @ApiStatus.Experimental
    @NotNull
    public ModificationTracker getIndexModificationTracker(@NotNull StubIndexKey<?, ?> indexId, @NotNull Project project) {
        return () -> getIndexModificationStamp(indexId, project);
    }

    public void flush() throws StorageException {
        if (!myInitialized) {
            return;
        }
        for (UpdatableIndex<?, Void, FileContent, ?> index : getAsyncState().myIndices.values()) {
            index.flush();
        }
    }


    public static boolean deleteWithRenamingIfExists(@NotNull Path file) {
        return Files.exists(file) && deleteWithRenaming(file.toFile());
    }

    public static boolean deleteWithRenaming(@NotNull File file) {
        File tempFileNameForDeletion = findSequentNonexistentFile(file.getParentFile(), file.getName(), "");
        boolean success = file.renameTo(tempFileNameForDeletion);
        return delete(success ? tempFileNameForDeletion:file);
    }


    @NonNull
    @Override
    public Logger getLogger() {
        return LOG;
    }

    private static class StubIndexStorageLayout<K> implements VfsAwareIndexStorageLayout<K, Void> {
        private final FileBasedIndexExtension<K, Void> myWrappedExtension;
        private final StubIndexKey<K, ?> myIndexKey;

        private StubIndexStorageLayout(FileBasedIndexExtension<K, Void> wrappedExtension, StubIndexKey<K, ?> indexKey) {
            myWrappedExtension = wrappedExtension;
            myIndexKey = indexKey;
        }

        @NonNull
        @Override
        public IndexStorage<K, Void> openIndexStorage() throws IOException {
//            if (FileBasedIndex.USE_IN_MEMORY_INDEX) {
//                return new InMemoryIndexStorage<>(myWrappedExtension.getKeyDescriptor());
//            }

            Path storageFile = IndexInfrastructure.getStorageFile(myIndexKey);
            try {
                return new VfsAwareMapIndexStorage<>(
                        storageFile,
                        myWrappedExtension.getKeyDescriptor(),
                        myWrappedExtension.getValueExternalizer(),
                        myWrappedExtension.getCacheSize(),
                        myWrappedExtension.keyIsUniqueForIndexedFile(),
                        myWrappedExtension.traceKeyHashToVirtualFileMapping(),
                        myWrappedExtension.enableWal()
                );
            }
            catch (IOException e) {
                IOUtil.deleteAllFilesStartingWith(storageFile);
                throw e;
            }
        }

        @Override
        public void clearIndexData() {
            throw new UnsupportedOperationException();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <Key> UpdatableIndex<Key, Void, FileContent, ?> getIndex(@NonNull StubIndexKey<Key,
            ?> indexKey) {
        return (UpdatableIndex<Key, Void, FileContent, ?>)getAsyncState().myIndices.get(indexKey);
    }

    @NonNull
    @Override
    public FileUpdateProcessor getPerFileElementTypeModificationTrackerUpdateProcessor() {
        return myPerFileElementTypeStubModificationTracker;
    }

    private final class StubIndexInitialization extends IndexDataInitializer<AsyncState> {
        private final AsyncState state = new AsyncState();
        private final IndexVersionRegistrationSink indicesRegistrationSink = new IndexVersionRegistrationSink();

        @NonNull
        @Override
        protected String getInitializationFinishedMessage(AsyncState initializationResult) {
            return "Initialized stub indexes: " + initializationResult.myIndices.keySet() + ".";
        }

        @Override
        protected AsyncState finish() {
            if (indicesRegistrationSink.hasChangedIndexes()) {
                final Throwable e = new Throwable(indicesRegistrationSink.changedIndices());
                // avoid direct forceRebuild as it produces dependency cycle (IDEA-105485)
                AppUIExecutor.onWriteThread(ModalityState.NON_MODAL).later().submit(() -> forceRebuild(e));
            }

            myInitialized = true;
            myStateFuture.complete(state);
            return state;
        }

        @NonNull
        @Override
        protected Collection<ThrowableRunnable<?>> prepareTasks() {
            Iterator<StubIndexExtension<?, ?>> extensionsIterator;
            if (IndexInfrastructure.hasIndices()) {
                extensionsIterator = StubIndexExtension.EP_NAME.getExtensionList().iterator();
            }
            else {
                extensionsIterator = Collections.emptyIterator();
            }

            boolean forceClean = Boolean.TRUE == myForcedClean.getAndSet(false);
            List<ThrowableRunnable<?>> tasks = new ArrayList<>();
            while (extensionsIterator.hasNext()) {
                StubIndexExtension<?, ?> extension = extensionsIterator.next();
                if (extension == null) {
                    break;
                }

                // initialize stub index keys
                extension.getKey();

                tasks.add(() -> registerIndexer(extension, forceClean, state, indicesRegistrationSink));
            }
            return tasks;
        }
    }
}
