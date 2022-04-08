package com.tyron.builder.api.internal.execution.steps;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.Try;
import com.tyron.builder.api.internal.execution.ExecutionOutcome;
import com.tyron.builder.api.internal.execution.ExecutionResult;
import com.tyron.builder.api.internal.execution.OutputChangeListener;
import com.tyron.builder.api.internal.execution.UnitOfWork;
import com.tyron.builder.api.internal.execution.history.AfterExecutionState;
import com.tyron.builder.api.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.api.internal.execution.history.impl.DefaultAfterExecutionState;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.api.internal.snapshot.SnapshotVisitResult;
import com.tyron.builder.api.internal.tasks.properties.TreeType;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.internal.BuildCacheController;
import com.tyron.builder.caching.internal.CacheableEntity;
import com.tyron.builder.caching.internal.controller.service.BuildCacheLoadResult;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiConsumer;

public class BuildCacheStep implements Step<IncrementalChangesContext, AfterExecutionResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildCacheStep.class);

    private final BuildCacheController buildCache;
    private final Deleter deleter;
    private final OutputChangeListener outputChangeListener;
    private final Step<? super IncrementalChangesContext, ? extends AfterExecutionResult> delegate;

    public BuildCacheStep(
            BuildCacheController buildCache,
            Deleter deleter,
            OutputChangeListener outputChangeListener,
            Step<? super IncrementalChangesContext, ? extends AfterExecutionResult> delegate
    ) {
        this.buildCache = buildCache;
        this.deleter = deleter;
        this.outputChangeListener = outputChangeListener;
        this.delegate = delegate;
    }

    @Override
    public AfterExecutionResult execute(UnitOfWork work, IncrementalChangesContext context) {
        return context.getCachingState().fold(
                cachingEnabled -> executeWithCache(work, context, cachingEnabled.getKey(), cachingEnabled.getBeforeExecutionState()),
                cachingDisabled -> executeWithoutCache(work, context)
        );
    }

    private AfterExecutionResult executeWithCache(UnitOfWork work, IncrementalChangesContext context, BuildCacheKey cacheKey, BeforeExecutionState beforeExecutionState) {
        CacheableWork cacheableWork = new CacheableWork(context.getIdentity().getUniqueId(), context.getWorkspace(), work);
        return Try.ofFailable(() -> work.isAllowedToLoadFromCache()
                ? buildCache.load(cacheKey, cacheableWork)
                : Optional.<BuildCacheLoadResult>empty()
        )
                .map(successfulLoad -> successfulLoad
                        .map(cacheHit -> {
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info("Loaded cache entry for " +  work.getDisplayName() + " with cache key " + cacheKey.getHashCode());
                            }
                            cleanLocalState(context.getWorkspace(), work);
                            OriginMetadata originMetadata = cacheHit.getOriginMetadata();
                            AfterExecutionState afterExecutionState = new DefaultAfterExecutionState(
                                    beforeExecutionState,
                                    cacheHit.getResultingSnapshots(),
                                    originMetadata,
                                    true);
                            return (AfterExecutionResult) new AfterExecutionResult() {
                                @Override
                                public Try<ExecutionResult> getExecutionResult() {
                                    return Try.successful(new ExecutionResult() {
                                        @Override
                                        public ExecutionOutcome getOutcome() {
                                            return ExecutionOutcome.FROM_CACHE;
                                        }

                                        @Override
                                        public Object getOutput() {
                                            return work.loadRestoredOutput(context.getWorkspace());
                                        }
                                    });
                                }

                                @Override
                                public Duration getDuration() {
                                    return originMetadata.getExecutionTime();
                                }

                                @Override
                                public Optional<AfterExecutionState> getAfterExecutionState() {
                                    return Optional.of(afterExecutionState);
                                }
                            };
                        })
                        .orElseGet(() -> executeAndStoreInCache(cacheableWork, cacheKey, context))
                )
                .getOrMapFailure(loadFailure -> {
                    throw new RuntimeException(
                            String.format("Failed to load cache entry %s for %s: %s",
                                    cacheKey.getHashCode(),
                                    work.getDisplayName(),
                                    loadFailure.getMessage()
                            ),
                            loadFailure
                    );
                });
    }

    private void cleanLocalState(File workspace, UnitOfWork work) {
        work.visitOutputs(workspace, new UnitOfWork.OutputVisitor() {
            @Override
            public void visitLocalState(File localStateRoot) {
                try {
                    outputChangeListener.beforeOutputChange(ImmutableList.of(localStateRoot.getAbsolutePath()));
                    deleter.deleteRecursively(localStateRoot);
                } catch (IOException ex) {
                    throw new UncheckedIOException(String.format("Failed to clean up local state files for %s: %s", work.getDisplayName(), localStateRoot), ex);
                }
            }
        });
    }

    private AfterExecutionResult executeAndStoreInCache(CacheableWork cacheableWork, BuildCacheKey cacheKey, IncrementalChangesContext context) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Did not find cache entry for " + cacheableWork.getDisplayName() + " with cache key " + cacheKey.getHashCode() + ", executing instead");
        }
        AfterExecutionResult result = executeWithoutCache(cacheableWork.work, context);
        result.getExecutionResult().ifSuccessfulOrElse(
                executionResult -> result.getAfterExecutionState()
                        .ifPresent(afterExecutionState -> store(cacheableWork, cacheKey, afterExecutionState.getOutputFilesProducedByWork(), afterExecutionState.getOriginMetadata().getExecutionTime())),
                failure -> LOGGER.debug("Not storing result of " + cacheableWork.getDisplayName() + " in cache because the execution failed")
        );
        return result;
    }

    private void store(CacheableWork work, BuildCacheKey cacheKey, ImmutableSortedMap<String, FileSystemSnapshot> outputFilesProducedByWork, Duration executionTime) {
        try {
            buildCache.store(cacheKey, work, outputFilesProducedByWork, executionTime);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Stored cache entry for " + work.getDisplayName() + " with cache key " + cacheKey.getHashCode());
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("Failed to store cache entry %s for %s: %s",
                            cacheKey.getHashCode(),
                            work.getDisplayName(),
                            e.getMessage()),
                    e);
        }
    }

    private AfterExecutionResult executeWithoutCache(UnitOfWork work, IncrementalChangesContext context) {
        return delegate.execute(work, context);
    }

    private static class CacheableWork implements CacheableEntity {
        private final String identity;
        private final File workspace;
        private final UnitOfWork work;

        public CacheableWork(String identity, File workspace, UnitOfWork work) {
            this.identity = identity;
            this.workspace = workspace;
            this.work = work;
        }

        @Override
        public String getIdentity() {
            return identity;
        }

        @Override
        public Class<?> getType() {
            return work.getClass();
        }

        @Override
        public String getDisplayName() {
            return work.getDisplayName();
        }

        @Override
        public void visitOutputTrees(CacheableTreeVisitor visitor) {
            work.visitOutputs(workspace, new UnitOfWork.OutputVisitor() {
                @Override
                public void visitOutputProperty(String propertyName, TreeType type, File root, FileCollection contents) {
                    visitor.visitOutputTree(propertyName, type, root);
                }
            });
        }
    }
}
