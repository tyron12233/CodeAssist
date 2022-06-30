package com.tyron.builder.caching.internal.controller;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Closer;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.BuildCacheService;
import com.tyron.builder.caching.internal.controller.operations.PackOperationDetails;
import com.tyron.builder.caching.internal.controller.operations.PackOperationResult;
import com.tyron.builder.caching.internal.controller.operations.UnpackOperationDetails;
import com.tyron.builder.caching.internal.controller.operations.UnpackOperationResult;
import com.tyron.builder.caching.internal.controller.service.BuildCacheServiceHandle;
import com.tyron.builder.caching.internal.controller.service.DefaultLocalBuildCacheServiceHandle;
import com.tyron.builder.caching.internal.controller.service.LoadTarget;
import com.tyron.builder.caching.internal.controller.service.LocalBuildCacheServiceHandle;
import com.tyron.builder.caching.internal.controller.service.NullBuildCacheServiceHandle;
import com.tyron.builder.caching.internal.controller.service.NullLocalBuildCacheServiceHandle;
import com.tyron.builder.caching.internal.controller.service.StoreTarget;
import com.tyron.builder.caching.internal.service.BuildCacheServicesConfiguration;
import com.tyron.builder.caching.local.internal.BuildCacheTempFileStore;
import com.tyron.builder.caching.local.internal.DefaultBuildCacheTempFileStore;
import com.tyron.builder.caching.local.internal.LocalBuildCacheService;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import javax.annotation.Nullable;

    public class DefaultBuildCacheController implements BuildCacheController {

    @VisibleForTesting
    final BuildCacheServiceHandle remote;

    @VisibleForTesting
    final LocalBuildCacheServiceHandle local;

    private final BuildCacheTempFileStore tmp;
    private final BuildOperationExecutor buildOperationExecutor;
    private final boolean emitDebugLogging;

    private boolean closed;

    public DefaultBuildCacheController(
            BuildCacheServicesConfiguration config,
            BuildOperationExecutor buildOperationExecutor,
            TemporaryFileProvider temporaryFileProvider,
            boolean logStackTraces,
            boolean emitDebugLogging,
            boolean disableRemoteOnError
    ) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.emitDebugLogging = emitDebugLogging;
        this.local = toLocalHandle(config.getLocal(), config.isLocalPush());
        this.remote = toRemoteHandle(config.getRemote(), config.isRemotePush(), buildOperationExecutor, logStackTraces, disableRemoteOnError);
        this.tmp = toTempFileStore(config.getLocal(), temporaryFileProvider);
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isEmitDebugLogging() {
        return emitDebugLogging;
    }

    @Override
    public <T> Optional<T> load(BuildCacheLoadCommand<T> command) {
        final Unpack<T> unpack = new Unpack<>(command);

        if (local.canLoad()) {
            try {
                local.load(command.getKey(), unpack);
            } catch (Exception e) {
                throw new BuildException("Build cache entry " + command.getKey().getHashCode() + " from local build cache is invalid", e);
            }

            if (unpack.result != null) {
                return Optional.of(unpack.result.getMetadata());
            }
        }

        if (remote.canLoad()) {
            tmp.withTempFile(command.getKey(), file -> {
                LoadTarget loadTarget = new LoadTarget(file);
                remote.load(command.getKey(), loadTarget);

                if (loadTarget.isLoaded()) {
                    try {
                        unpack.execute(file);
                    } catch (Exception e) {
                        throw new BuildException("Build cache entry " + command.getKey().getHashCode() + " from remote build cache is invalid", e);
                    }
                    if (local.canStore()) {
                        local.store(command.getKey(), file);
                    }
                }
            });
        }

        BuildCacheLoadCommand.Result<T> result = unpack.result;
        return result == null
                ? Optional.empty()
                : Optional.of(result.getMetadata());
    }

    private class Unpack<T> implements Action<File> {
        private final BuildCacheLoadCommand<T> command;

        private BuildCacheLoadCommand.Result<T> result;

        private Unpack(BuildCacheLoadCommand<T> command) {
            this.command = command;
        }

        @Override
        public void execute(File file) {
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) throws IOException {
                    try (InputStream input = new FileInputStream(file)) {
                        result = command.load(input);
                        context.setResult(new UnpackOperationResult(
                                result.getArtifactEntryCount()
                        ));
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Unpack build cache entry " + command.getKey().getHashCode())
                            .details(new UnpackOperationDetails(command.getKey(), file.length()))
                            .progressDisplayName("Unpacking build cache entry");
                }
            });
        }
    }

    @Override
    public void store(BuildCacheStoreCommand command) {
        if (!local.canStore() && !remote.canStore()) {
            return;
        }

        BuildCacheKey key = command.getKey();
        Pack pack = new Pack(command);

        tmp.withTempFile(command.getKey(), file -> {
            pack.execute(file);

            if (remote.canStore()) {
                remote.store(key, new StoreTarget(file));
            }

            if (local.canStore()) {
                local.store(key, file);
            }
        });
    }

    private class Pack implements Action<File> {

        private final BuildCacheStoreCommand command;

        private Pack(BuildCacheStoreCommand command) {
            this.command = command;
        }

        @Override
        public void execute(final File file) {
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) throws IOException {
                    try (FileOutputStream fileOutputStream = new FileOutputStream(file)) {
                        BuildCacheStoreCommand.Result result = command.store(fileOutputStream);
                        context.setResult(new PackOperationResult(
                                result.getArtifactEntryCount(),
                                file.length()
                        ));
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor.displayName("Pack build cache entry " + command.getKey())
                            .details(new PackOperationDetails(command.getKey()))
                            .progressDisplayName("Packing build cache entry");
                }
            });
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            Closer closer = Closer.create();
            closer.register(local);
            closer.register(remote);
            closer.close();
        }
    }

    private static BuildCacheServiceHandle toRemoteHandle(@Nullable BuildCacheService service, boolean push, BuildOperationExecutor buildOperationExecutor, boolean logStackTraces, boolean disableOnError) {
        return NullBuildCacheServiceHandle.INSTANCE;
//        return service == null
//                ? NullBuildCacheServiceHandle.INSTANCE
//                : new OpFiringBuildCacheServiceHandle(service, push, BuildCacheServiceRole.REMOTE, buildOperationExecutor, logStackTraces, disableOnError);
    }

    private static LocalBuildCacheServiceHandle toLocalHandle(@Nullable LocalBuildCacheService local, boolean localPush) {
        return local == null
                ? NullLocalBuildCacheServiceHandle.INSTANCE
                : new DefaultLocalBuildCacheServiceHandle(local, localPush);
    }

    private static BuildCacheTempFileStore toTempFileStore(@Nullable LocalBuildCacheService local, TemporaryFileProvider temporaryFileProvider) {
        return local != null
                ? local
                : new DefaultBuildCacheTempFileStore(temporaryFileProvider);
    }
}
