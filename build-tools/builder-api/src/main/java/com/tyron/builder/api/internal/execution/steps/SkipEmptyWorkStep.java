package com.tyron.builder.api.internal.execution.steps;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.internal.Try;
import com.tyron.builder.api.internal.execution.ExecutionOutcome;
import com.tyron.builder.api.internal.execution.ExecutionResult;
import com.tyron.builder.api.internal.execution.OutputChangeListener;
import com.tyron.builder.api.internal.execution.UnitOfWork;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.execution.caching.CachingState;
import com.tyron.builder.api.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.api.internal.execution.history.AfterExecutionState;
import com.tyron.builder.api.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.api.internal.execution.history.OutputsCleaner;
import com.tyron.builder.api.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.api.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.internal.snapshot.SnapshotUtil;
import com.tyron.builder.api.internal.snapshot.ValueSnapshot;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.internal.time.Timer;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class SkipEmptyWorkStep implements Step<PreviousExecutionContext, CachingResult> {

    private static final Logger LOGGER = Logger.getLogger(SkipEmptyWorkStep.class.getSimpleName());

    private final OutputChangeListener outputChangeListener;
    private final WorkInputListeners workInputListeners;
    private final Supplier<OutputsCleaner> outputsCleanerSupplier;
    private final Step<? super PreviousExecutionContext, ? extends CachingResult> delegate;

    public SkipEmptyWorkStep(
            OutputChangeListener outputChangeListener,
            WorkInputListeners workInputListeners,
            Supplier<OutputsCleaner> outputsCleanerSupplier,
            Step<? super PreviousExecutionContext, ? extends CachingResult> delegate
    ) {
        this.outputChangeListener = outputChangeListener;
        this.workInputListeners = workInputListeners;
        this.outputsCleanerSupplier = outputsCleanerSupplier;
        this.delegate = delegate;
    }

    @Override
    public CachingResult execute(UnitOfWork work, PreviousExecutionContext context) {
        ImmutableSortedMap<String, CurrentFileCollectionFingerprint> knownFileFingerprints = context.getInputFileProperties();
        ImmutableSortedMap<String, ValueSnapshot> knownValueSnapshots = context.getInputProperties();
        InputFingerprinter.Result newInputs = fingerprintPrimaryInputs(work, context, knownFileFingerprints, knownValueSnapshots);

        ImmutableSortedMap<String, CurrentFileCollectionFingerprint> sourceFileProperties = newInputs.getFileFingerprints();
        if (!sourceFileProperties.isEmpty()) {
            if (hasEmptySources(sourceFileProperties, newInputs.getPropertiesRequiringIsEmptyCheck(), work)
            ) {
                return skipExecutionWithEmptySources(work, context);
            } else {
                return executeWithNoEmptySources(work, context, newInputs.getAllFileFingerprints());
            }
        } else {
            return executeWithNoEmptySources(work, context);
        }
    }

    private boolean hasEmptySources(ImmutableSortedMap<String, CurrentFileCollectionFingerprint> sourceFileProperties, ImmutableSet<String> propertiesRequiringIsEmptyCheck, UnitOfWork work) {
        if (propertiesRequiringIsEmptyCheck.isEmpty()) {
            return sourceFileProperties.values().stream()
                    .allMatch(CurrentFileCollectionFingerprint::isEmpty);
        } else {
            // We need to check the underlying file collections for properties in propertiesRequiringIsEmptyCheck,
            // since those are backed by files which may be empty archives.
            // And being empty archives is not reflected in the fingerprint.
            return hasEmptyFingerprints(sourceFileProperties, propertyName -> !propertiesRequiringIsEmptyCheck.contains(propertyName))
                   && hasEmptyInputFileCollections(work, propertiesRequiringIsEmptyCheck::contains);
        }
    }

    private boolean hasEmptyFingerprints(ImmutableSortedMap<String, CurrentFileCollectionFingerprint> sourceFileProperties, Predicate<String> propertyNameFilter) {
        return sourceFileProperties.entrySet().stream()
                .filter(entry -> propertyNameFilter.test(entry.getKey()))
                .map(Map.Entry::getValue)
                .allMatch(CurrentFileCollectionFingerprint::isEmpty);
    }

    private boolean hasEmptyInputFileCollections(UnitOfWork work, Predicate<String> propertyNameFilter) {
        EmptyCheckingVisitor visitor = new EmptyCheckingVisitor(propertyNameFilter);
        work.visitRegularInputs(visitor);
        return visitor.isAllEmpty();
    }


    private InputFingerprinter.Result fingerprintPrimaryInputs(UnitOfWork work, PreviousExecutionContext context, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> knownFileFingerprints, ImmutableSortedMap<String, ValueSnapshot> knownValueSnapshots) {
        return work.getInputFingerprinter().fingerprintInputProperties(
                context.getPreviousExecutionState()
                        .map(PreviousExecutionState::getInputProperties)
                        .orElse(ImmutableSortedMap.of()),
                context.getPreviousExecutionState()
                        .map(PreviousExecutionState::getInputFileProperties)
                        .orElse(ImmutableSortedMap.of()),
                knownValueSnapshots,
                knownFileFingerprints,
                visitor -> work.visitRegularInputs(new InputFingerprinter.InputVisitor() {
                    @Override
                    public void visitInputFileProperty(String propertyName, InputFingerprinter.InputPropertyType type, InputFingerprinter.FileValueSupplier value) {
                        if (type == InputFingerprinter.InputPropertyType.PRIMARY) {
                            visitor.visitInputFileProperty(propertyName, type, value);
                        }
                    }
                }));
    }

    private CachingResult skipExecutionWithEmptySources(UnitOfWork work, PreviousExecutionContext context) {
        ImmutableSortedMap<String, FileSystemSnapshot> outputFilesAfterPreviousExecution = context.getPreviousExecutionState()
                .map(PreviousExecutionState::getOutputFilesProducedByWork)
                .orElse(ImmutableSortedMap.of());

        ExecutionOutcome skipOutcome;
        Timer timer = Time.startTimer();
        if (outputFilesAfterPreviousExecution.isEmpty()) {
            LOGGER.info("Skipping " + work.getDisplayName() + " as it has no source files and no previous output files.");
            skipOutcome = ExecutionOutcome.SHORT_CIRCUITED;
        } else {
            boolean didWork = cleanPreviousTaskOutputs(outputFilesAfterPreviousExecution);
            if (didWork) {
                LOGGER.info("Cleaned previous output of " + work.getDisplayName() + " as it has no source files.");
                skipOutcome = ExecutionOutcome.EXECUTED_NON_INCREMENTALLY;
            } else {
                skipOutcome = ExecutionOutcome.SHORT_CIRCUITED;
            }
        }
        Duration duration = skipOutcome == ExecutionOutcome.SHORT_CIRCUITED ? Duration.ZERO : Duration.ofMillis(timer.getElapsedMillis());

        broadcastWorkInputs(work, true);

        return new CachingResult() {
            @Override
            public Duration getDuration() {
                return duration;
            }

            @Override
            public Try<ExecutionResult> getExecutionResult() {
                return Try.successful(new ExecutionResult() {
                    @Override
                    public ExecutionOutcome getOutcome() {
                        return skipOutcome;
                    }

                    @Override
                    public Object getOutput() {
                        return work.loadRestoredOutput(context.getWorkspace());
                    }
                });
            }

            @Override
            public CachingState getCachingState() {
                return CachingState.NOT_DETERMINED;
            }

            @Override
            public ImmutableList<String> getExecutionReasons() {
                return ImmutableList.of();
            }

            @Override
            public Optional<AfterExecutionState> getAfterExecutionState() {
                return Optional.empty();
            }

            @Override
            public Optional<OriginMetadata> getReusedOutputOriginMetadata() {
                return Optional.empty();
            }
        };
    }

    private CachingResult executeWithNoEmptySources(UnitOfWork work, PreviousExecutionContext context, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> newInputFileProperties) {
        return executeWithNoEmptySources(work, new PreviousExecutionContext() {
            @Override
            public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
                return newInputFileProperties;
            }

            @Override
            public Optional<PreviousExecutionState> getPreviousExecutionState() {
                return context.getPreviousExecutionState();
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
            public ImmutableSortedMap<String, ValueSnapshot> getInputProperties() {
                return context.getInputProperties();
            }

            @Override
            public UnitOfWork.Identity getIdentity() {
                return context.getIdentity();
            }

            @Override
            public Optional<String> getNonIncrementalReason() {
                return context.getNonIncrementalReason();
            }

            @Override
            public WorkValidationContext getValidationContext() {
                return context.getValidationContext();
            }
        });
    }

    private CachingResult executeWithNoEmptySources(UnitOfWork work, PreviousExecutionContext context) {
        broadcastWorkInputs(work, false);
        return delegate.execute(work, context);
    }

    private void broadcastWorkInputs(UnitOfWork work, boolean onlyPrimaryInputs) {
        workInputListeners.broadcastFileSystemInputsOf(work, onlyPrimaryInputs
                ? EnumSet.of(InputFingerprinter.InputPropertyType.PRIMARY)
                : EnumSet.allOf(InputFingerprinter.InputPropertyType.class));
    }

    private boolean cleanPreviousTaskOutputs(Map<String, FileSystemSnapshot> outputFileSnapshots) {
        OutputsCleaner outputsCleaner = outputsCleanerSupplier.get();
        for (FileSystemSnapshot outputFileSnapshot : outputFileSnapshots.values()) {
            try {
                outputChangeListener.beforeOutputChange(SnapshotUtil.rootIndex(outputFileSnapshot).keySet());
                outputsCleaner.cleanupOutputs(outputFileSnapshot);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return outputsCleaner.getDidWork();
    }

    private static class EmptyCheckingVisitor implements InputFingerprinter.InputVisitor {
        private final Predicate<String> propertyNameFilter;
        private boolean allEmpty = true;

        public EmptyCheckingVisitor(Predicate<String> propertyNameFilter) {
            this.propertyNameFilter = propertyNameFilter;
        }

        @Override
        public void visitInputFileProperty(String propertyName, InputFingerprinter.InputPropertyType type, InputFingerprinter.FileValueSupplier value) {
            if (propertyNameFilter.test(propertyName)) {
                allEmpty = allEmpty && value.getFiles().isEmpty();
            }
        }

        public boolean isAllEmpty() {
            return allEmpty;
        }
    }
}
