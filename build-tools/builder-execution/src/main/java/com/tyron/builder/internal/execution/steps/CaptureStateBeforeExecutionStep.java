package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.internal.execution.OutputSnapshotter;
import com.tyron.builder.internal.execution.OutputSnapshotter.OutputFileSnapshottingException;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter;
import com.tyron.builder.internal.execution.fingerprint.InputFingerprinter.InputFileFingerprintingException;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.ExecutionHistoryStore;
import com.tyron.builder.internal.execution.history.InputExecutionState;
import com.tyron.builder.internal.execution.history.OverlappingOutputDetector;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.execution.history.impl.DefaultBeforeExecutionState;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationType;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.ValueSnapshot;
import com.tyron.builder.internal.snapshot.impl.ImplementationSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.Optional;

public class CaptureStateBeforeExecutionStep<C extends PreviousExecutionContext, R extends CachingResult> extends BuildOperationStep<C, R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureStateBeforeExecutionStep.class);

    private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
    private final OutputSnapshotter outputSnapshotter;
    private final OverlappingOutputDetector overlappingOutputDetector;
    private final Step<? super BeforeExecutionContext, ? extends R> delegate;

    public CaptureStateBeforeExecutionStep(
            BuildOperationExecutor buildOperationExecutor,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            OutputSnapshotter outputSnapshotter,
            OverlappingOutputDetector overlappingOutputDetector,
            Step<? super BeforeExecutionContext, ? extends R> delegate
    ) {
        super(buildOperationExecutor);
        this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        this.outputSnapshotter = outputSnapshotter;
        this.overlappingOutputDetector = overlappingOutputDetector;
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        Optional<BeforeExecutionState> beforeExecutionState = context.getHistory()
                .flatMap(history -> captureExecutionState(work, context));
        return delegate.execute(work, new BeforeExecutionContext() {
            @Override
            public Optional<BeforeExecutionState> getBeforeExecutionState() {
                return beforeExecutionState;
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
                return getBeforeExecutionState()
                        .map(BeforeExecutionState::getInputProperties)
                        .orElseGet(context::getInputProperties);
            }

            @Override
            public ImmutableSortedMap<String, CurrentFileCollectionFingerprint> getInputFileProperties() {
                return getBeforeExecutionState()
                        .map(BeforeExecutionState::getInputFileProperties)
                        .orElseGet(context::getInputFileProperties);
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
        });
    }

    @Nonnull
    private Optional<BeforeExecutionState> captureExecutionState(UnitOfWork work, PreviousExecutionContext context) {
        return operation(operationContext -> {
                    ImmutableSortedMap<String, FileSystemSnapshot> unfilteredOutputSnapshots;
                    try {
                        unfilteredOutputSnapshots = outputSnapshotter.snapshotOutputs(work, context.getWorkspace());
                    } catch (OutputFileSnapshottingException e) {
                        work.handleUnreadableOutputs(e);
                        operationContext.setResult(Operation.Result.INSTANCE);
                        return Optional.empty();
                    }

                    try {
                        BeforeExecutionState executionState = captureExecutionStateWithOutputs(work, context, unfilteredOutputSnapshots);
                        operationContext.setResult(Operation.Result.INSTANCE);
                        return Optional.of(executionState);
                    } catch (InputFileFingerprintingException e) {
                        // Note that we let InputFingerprintException fall through as we've already
                        // been failing for non-file value fingerprinting problems even for tasks
                        work.handleUnreadableInputs(e);
                        operationContext.setResult(Operation.Result.INSTANCE);
                        return Optional.empty();
                    }
                },
                BuildOperationDescriptor
                        .displayName("Snapshot inputs and outputs before executing " + work.getDisplayName())
                        .details(Operation.Details.INSTANCE)
        );
    }

    private BeforeExecutionState captureExecutionStateWithOutputs(UnitOfWork work, PreviousExecutionContext context, ImmutableSortedMap<String, FileSystemSnapshot> unfilteredOutputSnapshots) {
        Optional<PreviousExecutionState> previousExecutionState = context.getPreviousExecutionState();

        ImplementationsBuilder implementationsBuilder = new ImplementationsBuilder(classLoaderHierarchyHasher);
        work.visitImplementations(implementationsBuilder);
        ImplementationSnapshot implementation = implementationsBuilder.getImplementation();
        ImmutableList<ImplementationSnapshot> additionalImplementations = implementationsBuilder.getAdditionalImplementations();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Implementation for {}: {}", work.getDisplayName(), implementation);
            LOGGER.debug("Additional implementations for {}: {}", work.getDisplayName(), additionalImplementations);
        }

        ImmutableSortedMap<String, ValueSnapshot> previousInputProperties = previousExecutionState
                .map(InputExecutionState::getInputProperties)
                .orElse(ImmutableSortedMap.of());
        ImmutableSortedMap<String, ? extends FileCollectionFingerprint> previousInputFileFingerprints = previousExecutionState
                .map(InputExecutionState::getInputFileProperties)
                .orElse(ImmutableSortedMap.of());
        ImmutableSortedMap<String, FileSystemSnapshot> previousOutputSnapshots = previousExecutionState
                .map(PreviousExecutionState::getOutputFilesProducedByWork)
                .orElse(ImmutableSortedMap.of());

        OverlappingOutputs overlappingOutputs;
        switch (work.getOverlappingOutputHandling()) {
            case DETECT_OVERLAPS:
                overlappingOutputs = overlappingOutputDetector.detect(previousOutputSnapshots, unfilteredOutputSnapshots);
                break;
            case IGNORE_OVERLAPS:
                overlappingOutputs = null;
                break;
            default:
                throw new AssertionError();
        }

        InputFingerprinter.Result newInputs = work.getInputFingerprinter().fingerprintInputProperties(
                previousInputProperties,
                previousInputFileFingerprints,
                context.getInputProperties(),
                context.getInputFileProperties(),
                work::visitRegularInputs
        );

        return new DefaultBeforeExecutionState(
                implementation,
                additionalImplementations,
                newInputs.getAllValueSnapshots(),
                newInputs.getAllFileFingerprints(),
                unfilteredOutputSnapshots,
                overlappingOutputs
        );
    }

    private static class ImplementationsBuilder implements UnitOfWork.ImplementationVisitor {
        private final ClassLoaderHierarchyHasher classLoaderHierarchyHasher;
        private ImplementationSnapshot implementation;
        private final ImmutableList.Builder<ImplementationSnapshot> additionalImplementations = ImmutableList.builder();

        public ImplementationsBuilder(ClassLoaderHierarchyHasher classLoaderHierarchyHasher) {
            this.classLoaderHierarchyHasher = classLoaderHierarchyHasher;
        }

        @Override
        public void visitImplementation(Class<?> implementation) {
            visitImplementation(ImplementationSnapshot.of(implementation, classLoaderHierarchyHasher));
        }

        @Override
        public void visitImplementation(ImplementationSnapshot implementation) {
            if (this.implementation == null) {
                this.implementation = implementation;
            } else {
                this.additionalImplementations.add(implementation);
            }
        }

        public ImplementationSnapshot getImplementation() {
            if (implementation == null) {
                throw new IllegalStateException("No implementation is set");
            }
            return implementation;
        }

        public ImmutableList<ImplementationSnapshot> getAdditionalImplementations() {
            return additionalImplementations.build();
        }
    }

    /*
     * This operation is only used here temporarily. Should be replaced with a more stable operation in the long term.
     */
    public interface Operation extends BuildOperationType<Operation.Details, Operation.Result> {
        interface Details {
            Details INSTANCE = new Details() {
            };
        }

        interface Result {
            Result INSTANCE = new Result() {
            };
        }
    }
}
