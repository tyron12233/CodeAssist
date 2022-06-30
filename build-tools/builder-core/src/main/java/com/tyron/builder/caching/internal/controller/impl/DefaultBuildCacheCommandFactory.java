package com.tyron.builder.caching.internal.controller.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Interner;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.internal.CacheableEntity;
import com.tyron.builder.caching.internal.controller.BuildCacheCommandFactory;
import com.tyron.builder.caching.internal.controller.BuildCacheLoadCommand;
import com.tyron.builder.caching.internal.controller.BuildCacheStoreCommand;
import com.tyron.builder.caching.internal.origin.OriginMetadata;
import com.tyron.builder.caching.internal.origin.OriginMetadataFactory;
import com.tyron.builder.caching.internal.packaging.BuildCacheEntryPacker;
import com.tyron.builder.internal.file.FileMetadata.AccessType;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.file.TreeType;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.internal.vfs.FileSystemAccess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;

public class DefaultBuildCacheCommandFactory implements BuildCacheCommandFactory {

    private final BuildCacheEntryPacker packer;
    private final OriginMetadataFactory originMetadataFactory;
    private final FileSystemAccess fileSystemAccess;
    private final Interner<String> stringInterner;

    public DefaultBuildCacheCommandFactory(BuildCacheEntryPacker packer, OriginMetadataFactory originMetadataFactory, FileSystemAccess fileSystemAccess, Interner<String> stringInterner) {
        this.packer = packer;
        this.originMetadataFactory = originMetadataFactory;
        this.fileSystemAccess = fileSystemAccess;
        this.stringInterner = stringInterner;
    }

    @Override
    public BuildCacheLoadCommand<LoadMetadata> createLoad(BuildCacheKey cacheKey, CacheableEntity entity) {
        return new LoadCommand(cacheKey, entity);
    }

    @Override
    public BuildCacheStoreCommand createStore(BuildCacheKey cacheKey, CacheableEntity entity, Map<String, ? extends FileSystemSnapshot> snapshots, Duration executionTime) {
        return new StoreCommand(cacheKey, entity, snapshots, executionTime);
    }

    private class LoadCommand implements BuildCacheLoadCommand<LoadMetadata> {

        private final BuildCacheKey cacheKey;
        private final CacheableEntity entity;

        private LoadCommand(BuildCacheKey cacheKey, CacheableEntity entity) {
            this.cacheKey = cacheKey;
            this.entity = entity;
        }

        @Override
        public BuildCacheKey getKey() {
            return cacheKey;
        }

        @Override
        public BuildCacheLoadCommand.Result<LoadMetadata> load(InputStream input) throws IOException {
            ImmutableList.Builder<String> roots = ImmutableList.builder();
            entity.visitOutputTrees((name, type, root) -> roots.add(root.getAbsolutePath()));
            // TODO: Actually unpack the roots inside of the action
            fileSystemAccess.write(roots.build(), () -> {
            });
            BuildCacheEntryPacker.UnpackResult unpackResult = packer.unpack(entity, input, originMetadataFactory.createReader(entity));
            // TODO: Update the snapshots from the action
            ImmutableSortedMap<String, FileSystemSnapshot> snapshots = snapshotUnpackedData(unpackResult.getSnapshots());
            return new Result<LoadMetadata>() {
                @Override
                public long getArtifactEntryCount() {
                    return unpackResult.getEntries();
                }

                @Override
                public LoadMetadata getMetadata() {
                    return new LoadMetadata() {
                        @Override
                        public OriginMetadata getOriginMetadata() {
                            return unpackResult.getOriginMetadata();
                        }

                        @Override
                        public ImmutableSortedMap<String, FileSystemSnapshot> getResultingSnapshots() {
                            return snapshots;
                        }
                    };
                }
            };
        }

        private ImmutableSortedMap<String, FileSystemSnapshot> snapshotUnpackedData(Map<String, ? extends FileSystemLocationSnapshot> treeSnapshots) {
            ImmutableSortedMap.Builder<String, FileSystemSnapshot> builder = ImmutableSortedMap.naturalOrder();
            entity.visitOutputTrees((treeName, type, root) -> {
                FileSystemLocationSnapshot treeSnapshot = treeSnapshots.get(treeName);
                FileSystemLocationSnapshot resultingSnapshot;
                if (treeSnapshot == null) {
                    String internedAbsolutePath = stringInterner.intern(root.getAbsolutePath());
                    resultingSnapshot = new MissingFileSnapshot(internedAbsolutePath, AccessType.DIRECT);
                } else {
                    if (type == TreeType.FILE && treeSnapshot.getType() != FileType.RegularFile) {
                        throw new IllegalStateException(String.format("Only a regular file should be produced by unpacking tree '%s', but saw a %s", treeName, treeSnapshot.getType()));
                    }
                    resultingSnapshot = treeSnapshot;
                }
                fileSystemAccess.record(resultingSnapshot);
                builder.put(treeName, resultingSnapshot);
            });
            return builder.build();
        }
    }

    private class StoreCommand implements BuildCacheStoreCommand {

        private final BuildCacheKey cacheKey;
        private final CacheableEntity entity;
        private final Map<String, ? extends FileSystemSnapshot> snapshots;
        private final Duration executionTime;

        private StoreCommand(BuildCacheKey cacheKey, CacheableEntity entity, Map<String, ? extends FileSystemSnapshot> snapshots, Duration executionTime) {
            this.cacheKey = cacheKey;
            this.entity = entity;
            this.snapshots = snapshots;
            this.executionTime = executionTime;
        }

        @Override
        public BuildCacheKey getKey() {
            return cacheKey;
        }

        @Override
        public BuildCacheStoreCommand.Result store(OutputStream output) throws IOException {
            final BuildCacheEntryPacker.PackResult packResult = packer.pack(entity, snapshots, output, originMetadataFactory.createWriter(entity, executionTime));
            return packResult::getEntries;
        }
    }
}
