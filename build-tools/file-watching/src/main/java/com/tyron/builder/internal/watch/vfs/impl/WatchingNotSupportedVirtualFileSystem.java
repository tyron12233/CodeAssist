package com.tyron.builder.internal.watch.vfs.impl;

import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationRunner;
import com.tyron.builder.internal.operations.CallableBuildOperation;
import com.tyron.builder.internal.snapshot.SnapshotHierarchy;
import com.tyron.builder.internal.vfs.VirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.AbstractVirtualFileSystem;
import com.tyron.builder.internal.vfs.impl.VfsRootReference;
import com.tyron.builder.internal.watch.registry.WatchMode;
import com.tyron.builder.internal.watch.vfs.BuildFinishedFileSystemWatchingBuildOperationType;
import com.tyron.builder.internal.watch.vfs.BuildLifecycleAwareVirtualFileSystem;
import com.tyron.builder.internal.watch.vfs.BuildStartedFileSystemWatchingBuildOperationType;
import com.tyron.builder.internal.watch.vfs.FileSystemWatchingInformation;
import com.tyron.builder.internal.watch.vfs.VfsLogging;
import com.tyron.builder.internal.watch.vfs.WatchLogging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * A {@link VirtualFileSystem} which is not able to register any watches.
 */
public class WatchingNotSupportedVirtualFileSystem extends AbstractVirtualFileSystem implements BuildLifecycleAwareVirtualFileSystem, FileSystemWatchingInformation {

    private static final Logger LOGGER = LoggerFactory.getLogger(WatchingNotSupportedVirtualFileSystem.class);

    public WatchingNotSupportedVirtualFileSystem(VfsRootReference rootReference) {
        super(rootReference);
    }

    @Override
    protected SnapshotHierarchy updateNotifyingListeners(AbstractVirtualFileSystem.UpdateFunction updateFunction) {
        return updateFunction.update(SnapshotHierarchy.NodeDiffListener.NOOP);
    }

    @Override
    public boolean afterBuildStarted(
            WatchMode watchMode,
            VfsLogging vfsLogging,
            WatchLogging watchLogging,
            BuildOperationRunner buildOperationRunner
    ) {
        if (watchMode == WatchMode.ENABLED) {
            LOGGER.warn("Watching the file system is not supported.");
        }
        rootReference.update(vfsRoot -> buildOperationRunner.call(new CallableBuildOperation<SnapshotHierarchy>() {
            @Override
            public SnapshotHierarchy call(BuildOperationContext context) {
                context.setResult(BuildStartedFileSystemWatchingBuildOperationType.Result.WATCHING_DISABLED);
                return vfsRoot.empty();
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName(BuildStartedFileSystemWatchingBuildOperationType.DISPLAY_NAME)
                        .details(BuildStartedFileSystemWatchingBuildOperationType.Details.INSTANCE);
            }
        }));
        return false;
    }

    @Override
    public void registerWatchableHierarchy(File rootDirectoryForWatching) {
    }

    @Override
    public void beforeBuildFinished(
            WatchMode watchMode,
            VfsLogging vfsLogging,
            WatchLogging watchLogging,
            BuildOperationRunner buildOperationRunner,
            int maximumNumberOfWatchedHierarchies
    ) {
        rootReference.update(vfsRoot -> buildOperationRunner.call(new CallableBuildOperation<SnapshotHierarchy>() {
            @Override
            public SnapshotHierarchy call(BuildOperationContext context) {
                context.setResult(BuildFinishedFileSystemWatchingBuildOperationType.Result.WATCHING_DISABLED);
                return vfsRoot.empty();
            }

            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName(BuildFinishedFileSystemWatchingBuildOperationType.DISPLAY_NAME)
                        .details(BuildFinishedFileSystemWatchingBuildOperationType.Details.INSTANCE);
            }
        }));
    }

    @Override
    public boolean isWatchingAnyLocations() {
        return false;
    }
}
