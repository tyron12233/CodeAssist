package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.Try;
import com.tyron.builder.internal.execution.ExecutionResult;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.internal.execution.caching.CachingDisabledReasonCategory;
import com.tyron.builder.internal.execution.caching.CachingState;
import com.tyron.builder.internal.execution.caching.CachingStateFactory;
import com.tyron.builder.internal.execution.caching.impl.DefaultCachingStateFactory;
import com.tyron.builder.internal.execution.history.AfterExecutionState;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.internal.controller.BuildCacheController;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

import java.io.File;
import java.time.Duration;
import java.util.Formatter;
import java.util.List;
import java.util.Optional;

public class ResolveCachingStateStep<C extends ValidationFinishedContext> implements Step<C, CachingResult> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveCachingStateStep.class);
    private static final CachingDisabledReason BUILD_CACHE_DISABLED_REASON = new CachingDisabledReason(
            CachingDisabledReasonCategory.BUILD_CACHE_DISABLED, "Build cache is disabled");
    private static final CachingState BUILD_CACHE_DISABLED_STATE = CachingState.disabledWithoutInputs(BUILD_CACHE_DISABLED_REASON);
    private static final CachingDisabledReason VALIDATION_FAILED_REASON = new CachingDisabledReason(CachingDisabledReasonCategory.VALIDATION_FAILURE, "Caching has been disabled to ensure correctness. Please consult deprecation warnings for more details.");
    private static final CachingState VALIDATION_FAILED_STATE = CachingState.disabledWithoutInputs(VALIDATION_FAILED_REASON);

    private final BuildCacheController buildCache;
    private final boolean buildScansEnabled;
    private final Step<? super CachingContext, ? extends UpToDateResult> delegate;

    public ResolveCachingStateStep(
            BuildCacheController buildCache,
            boolean buildScansEnabled,
            Step<? super CachingContext, ? extends UpToDateResult> delegate
    ) {
        this.buildCache = buildCache;
        this.buildScansEnabled = buildScansEnabled;
        this.delegate = delegate;
    }

    @Override
    public CachingResult execute(UnitOfWork work, C context) {
        CachingState cachingState;
        if (!buildCache.isEnabled() && !buildScansEnabled) {
            cachingState = BUILD_CACHE_DISABLED_STATE;
        } else if (context.getValidationProblems().isPresent()) {
            cachingState = VALIDATION_FAILED_STATE;
        } else {
            cachingState = context.getBeforeExecutionState()
                    .map(beforeExecutionState -> calculateCachingState(work, beforeExecutionState))
                    .orElseGet(() -> calculateCachingStateWithNoCapturedInputs(work));
        }

        cachingState.apply(
                enabled -> logCacheKey(enabled.getKey(), work),
                disabled -> logDisabledReasons(disabled.getDisabledReasons(), work)
        );

        UpToDateResult result = delegate.execute(work, new CachingContext() {
            @Override
            public CachingState getCachingState() {
                return cachingState;
            }

            @Override
            public Optional<String> getNonIncrementalReason() {
                return context.getNonIncrementalReason();
            }

            @Override
            public WorkValidationContext getValidationContext() {
                return context.getValidationContext();
            }

            @Override
            public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
                return context.getInputProperties();
            }

            @Override
            public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
                return context.getInputFileProperties();
            }

            @Override
            public UnitOfWork.Identity getIdentity() {
                return context.getIdentity();
            }

            @Override
            public File getWorkspace() {
                return context.getWorkspace();
            }

            @Override
            public Optional<ExecutionHistoryStore> getHistory() {
                return context.getHistory();
            }

            @Override
            public Optional<PreviousExecutionState> getPreviousExecutionState() {
                return context.getPreviousExecutionState();
            }

            @Override
            public Optional<ValidationResult> getValidationProblems() {
                return context.getValidationProblems();
            }

            @Override
            public Optional<BeforeExecutionState> getBeforeExecutionState() {
                return context.getBeforeExecutionState();
            }
        });
        return new CachingResult() {
            @Override
            public CachingState getCachingState() {
                return cachingState;
            }

            @Override
            public ImmutableList<String> getExecutionReasons() {
                return result.getExecutionReasons();
            }

            @Override
            public Optional<AfterExecutionState> getAfterExecutionState() {
                return result.getAfterExecutionState();
            }

            @Override
            public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                return result.getReusedOutputOriginMetadata();
            }

            @Override
            public Try<ExecutionResult> getExecutionResult() {
                return result.getExecutionResult();
            }

            @Override
            public Duration getDuration() {
                return result.getDuration();
            }
        };
    }

    private CachingState calculateCachingState(UnitOfWork work, BeforeExecutionState beforeExecutionState) {
        Logger logger = buildCache.isEmitDebugLogging()
                ? LOGGER
                : NOPLogger.NOP_LOGGER;
        CachingStateFactory cachingStateFactory = new DefaultCachingStateFactory(logger);

        ImmutableList.Builder<CachingDisabledReason> cachingDisabledReasonsBuilder = ImmutableList.builder();
        if (!buildCache.isEnabled()) {
            cachingDisabledReasonsBuilder.add(BUILD_CACHE_DISABLED_REASON);
        }
        OverlappingOutputs detectedOverlappingOutputs = beforeExecutionState.getDetectedOverlappingOutputs()
                .orElse(null);
        work.shouldDisableCaching(detectedOverlappingOutputs)
                .ifPresent(cachingDisabledReasonsBuilder::add);

        return cachingStateFactory.createCachingState(beforeExecutionState, cachingDisabledReasonsBuilder.build());
    }

    private CachingState calculateCachingStateWithNoCapturedInputs(UnitOfWork work) {
        if (!buildCache.isEnabled()) {
            return BUILD_CACHE_DISABLED_STATE;
        }
        return work.shouldDisableCaching(null)
                .map(CachingState::disabledWithoutInputs)
                .orElse(CachingState.NOT_DETERMINED);
    }

    private void logCacheKey(BuildCacheKey cacheKey, UnitOfWork work) {
        if (buildCache.isEmitDebugLogging()) {
            LOGGER.warn("Build cache key for {} is {}", work.getDisplayName(), cacheKey.getDisplayName());
        } else {
            LOGGER.info("Build cache key for {} is {}", work.getDisplayName(), cacheKey.getDisplayName());
        }
    }

    private void logDisabledReasons(List<CachingDisabledReason> reasons, UnitOfWork work) {
        Formatter formatter = new Formatter();
        formatter.format("Caching disabled for %s because:", work.getDisplayName());
        for (CachingDisabledReason reason : reasons) {
            formatter.format("%n  %s", reason.getMessage());
        }
        LOGGER.info(formatter.toString());
    }
}
