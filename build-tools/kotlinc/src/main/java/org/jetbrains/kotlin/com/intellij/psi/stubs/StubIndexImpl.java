package org.jetbrains.kotlin.com.intellij.psi.stubs;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointListener;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.ProgressIndicatorUtils;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.ModificationTracker;
import org.jetbrains.kotlin.com.intellij.psi.tree.StubFileElementType;
import org.jetbrains.kotlin.com.intellij.util.SystemProperties;
import org.jetbrains.kotlin.com.intellij.util.containers.CollectionFactory;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.kotlin.com.intellij.util.indexing.FileContent;
import org.jetbrains.kotlin.com.intellij.util.indexing.ID;
import org.jetbrains.kotlin.com.intellij.util.indexing.IndexDataInitializer;
import org.jetbrains.kotlin.com.intellij.util.indexing.UpdatableIndex;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class StubIndexImpl extends StubIndexEx {

    static final Logger LOG = Logger.getInstance(StubIndexImpl.class);

    public enum PerFileElementTypeStubChangeTrackingSource {
        Disabled,
        ChangedFilesCollector
    }

    public static final PerFileElementTypeStubChangeTrackingSource
            PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE;

    static {
        int sourceId = SystemProperties.getIntProperty(
                "stub.index.per.file.element.type.stub.change.tracking.source",
                1);
        PER_FILE_ELEMENT_TYPE_STUB_CHANGE_TRACKING_SOURCE =
                PerFileElementTypeStubChangeTrackingSource.values()[sourceId];
    }

    private static final class AsyncState {
        private final Map<StubIndexKey<?, ?>, UpdatableIndex<?, Void, FileContent, ?>> myIndices =
                CollectionFactory.createSmallMemoryFootprintMap();
    }

    private final AtomicBoolean myForcedClean = new AtomicBoolean();
    private volatile CompletableFuture<AsyncState> myStateFuture;
    private volatile AsyncState myState;
    private volatile boolean myInitialized;

    private final @NonNull PerFileElementTypeStubModificationTracker
            myPerFileElementTypeStubModificationTracker;

    public StubIndexImpl() {
        StubIndexExtension.EP_NAME.addExtensionPointListener(new ExtensionPointListener<StubIndexExtension<?, ?>>() {
            @Override
            public void extensionRemoved(@NonNull StubIndexExtension<?, ?> extension,
                                         @NonNull PluginDescriptor pluginDescriptor) {
                ID.unloadId(extension.getKey());
            }
        }, null);
        myPerFileElementTypeStubModificationTracker =
                new PerFileElementTypeStubModificationTracker();
    }

    private AsyncState getAsyncState() {
        AsyncState state = myState; // memory barrier
        if (state == null) {
//            if (myStateFuture == null) {
//                ((FileBasedIndexImpl) FileBasedIndex.getInstance())
//                .waitUntilIndicesAreInitialized();
//            }
//            if (ProgressManager.getInstance().isInNonCancelableSection()) {
//                try {
//                    state = myStateFuture.get();
//                }
//                catch (Exception e) {
//                    FileBasedIndexImpl.LOG.error(e);
//                }
//            }
//            else {
//                CompletableFuture<AsyncState> future = myStateFuture;
//                if (future == null) {
//                    throw new CancellationException("Stub Index is already disposed");
//                }
//                state = ProgressIndicatorUtils.awaitWithCheckCanceled(future);
//            }
//            myState = state;
        }
        return state;
    }

    @Override
    public void forceRebuild(@NonNull Throwable e) {
        FileBasedIndex.getInstance().scheduleRebuild(StubUpdatingIndex.INDEX_ID, e);
    }

    @NonNull
    @Override
    public ModificationTracker getPerFileElementTypeModificationTracker(@NonNull StubFileElementType<?> fileElementType) {
        return null;
    }

    @NonNull
    @Override
    public ModificationTracker getStubIndexModificationTracker(@NonNull Project project) {
        return null;
    }

    @Override
    public void initializeStubIndexes() {
        assert !myInitialized;

        // might be called on the same thread twice if initialization has been failed
        if (myStateFuture == null) {
            // ensure that FileBasedIndex task "FileIndexDataInitialization" submitted first
            FileBasedIndex.getInstance();

            myStateFuture = new CompletableFuture<>();
//            IndexDataInitializer.submitGenesisTask(new StubIndexInitialization());
        }
    }

    @Override
    public void initializationFailed(@NonNull Throwable error) {

    }

    @NonNull
    @Override
    public Logger getLogger() {
        return Logger.getInstance(StubIndexImpl.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <Key> UpdatableIndex<Key, Void, FileContent, ?> getIndex(@NonNull StubIndexKey<Key,
            ?> indexKey) {
        return (UpdatableIndex<Key, Void, FileContent, ?>) getAsyncState().myIndices.get(indexKey);
    }

    @NonNull
    @Override
    public FileUpdateProcessor getPerFileElementTypeModificationTrackerUpdateProcessor() {
        return null;
    }
}
