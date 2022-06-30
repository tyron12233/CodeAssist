package com.tyron.builder.internal.execution.history.changes;

import static com.tyron.builder.internal.execution.history.impl.OutputSnapshotUtil.findOutputsStillPresentSincePreviousExecution;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.OutputFileChanges;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.impl.KnownImplementationSnapshot;

public class DefaultExecutionStateChangeDetector implements ExecutionStateChangeDetector {
    @Override
    public ExecutionStateChanges detectChanges(
            Describable executable,
            PreviousExecutionState lastExecution,
            BeforeExecutionState thisExecution,
            IncrementalInputProperties incrementalInputProperties
    ) {
        // Capture changes in execution outcome
        ChangeContainer previousSuccessState = new PreviousSuccessChanges(
                lastExecution.isSuccessful());

        // Capture changes to implementation

        // After validation, the current implementations can't be unknown when detecting changes.
        // Previous implementations can still be unknown, since we store the inputs in the task history even if validation fails.
        // When we fail the build for unknown implementations, then the previous implementations also can't be unknown.
        KnownImplementationSnapshot currentImplementation = Cast.uncheckedNonnullCast(thisExecution.getImplementation());
        ImmutableList<KnownImplementationSnapshot> currentAdditionalImplementations = Cast.uncheckedNonnullCast(thisExecution.getAdditionalImplementations());
        ChangeContainer implementationChanges = new ImplementationChanges(
                lastExecution.getImplementation(), lastExecution.getAdditionalImplementations(),
                currentImplementation, currentAdditionalImplementations,
                executable);

        // Capture non-file input changes
        ChangeContainer inputPropertyChanges = new PropertyChanges(
                lastExecution.getInputProperties().keySet(),
                thisExecution.getInputProperties().keySet(),
                "Input",
                executable);
        ChangeContainer inputPropertyValueChanges = new InputValueChanges(
                lastExecution.getInputProperties(),
                thisExecution.getInputProperties(),
                executable);

        // Capture input files state
        ChangeContainer inputFilePropertyChanges = new PropertyChanges(
                lastExecution.getInputFileProperties().keySet(),
                thisExecution.getInputFileProperties().keySet(),
                "Input file",
                executable);
        InputFileChanges nonIncrementalInputFileChanges = incrementalInputProperties.nonIncrementalChanges(
                lastExecution.getInputFileProperties(),
                thisExecution.getInputFileProperties()
        );

        // Capture output files state
        ChangeContainer outputFilePropertyChanges = new PropertyChanges(
                lastExecution.getOutputFilesProducedByWork().keySet(),
                thisExecution.getOutputFileLocationSnapshots().keySet(),
                "Output",
                executable);
        ImmutableSortedMap<String, FileSystemSnapshot> remainingPreviouslyProducedOutputs = thisExecution.getDetectedOverlappingOutputs().isPresent()
                ? findOutputsStillPresentSincePreviousExecution(lastExecution.getOutputFilesProducedByWork(), thisExecution.getOutputFileLocationSnapshots())
                : thisExecution.getOutputFileLocationSnapshots();
        OutputFileChanges outputFileChanges = new OutputFileChanges(
                lastExecution.getOutputFilesProducedByWork(),
                remainingPreviouslyProducedOutputs
        );

        // Collect changes that would trigger a rebuild
        ChangeContainer rebuildTriggeringChanges = errorHandling(executable, new SummarizingChangeContainer(
                previousSuccessState,
                implementationChanges,
                inputPropertyChanges,
                inputPropertyValueChanges,
                outputFilePropertyChanges,
                outputFileChanges,
                inputFilePropertyChanges,
                nonIncrementalInputFileChanges
        ));
        ImmutableList<String> rebuildReasons = collectChanges(rebuildTriggeringChanges);

        if (!rebuildReasons.isEmpty()) {
            return ExecutionStateChanges.nonIncremental(
                    rebuildReasons,
                    thisExecution,
                    incrementalInputProperties
            );
        } else {
            // Collect incremental input changes
            InputFileChanges directIncrementalInputFileChanges = incrementalInputProperties.incrementalChanges(
                    lastExecution.getInputFileProperties(),
                    thisExecution.getInputFileProperties()
            );
            InputFileChanges incrementalInputFileChanges = errorHandling(executable, caching(directIncrementalInputFileChanges));
            ImmutableList<String> incrementalInputFileChangeMessages = collectChanges(incrementalInputFileChanges);
            return ExecutionStateChanges.incremental(
                    incrementalInputFileChangeMessages,
                    thisExecution,
                    incrementalInputFileChanges,
                    incrementalInputProperties
            );
        }
    }

    private static ImmutableList<String> collectChanges(ChangeContainer changes) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        MessageCollectingChangeVisitor visitor = new MessageCollectingChangeVisitor(builder,
                MAX_OUT_OF_DATE_MESSAGES);
        changes.accept(visitor);
        return builder.build();
    }

    private static InputFileChanges caching(InputFileChanges wrapped) {
        CachingChangeContainer cachingChangeContainer = new CachingChangeContainer(MAX_OUT_OF_DATE_MESSAGES, wrapped);
        return new InputFileChangesWrapper(wrapped, cachingChangeContainer);
    }

    private static ChangeContainer errorHandling(Describable executable, ChangeContainer wrapped) {
        return new ErrorHandlingChangeContainer(executable, wrapped);
    }

    private static InputFileChanges errorHandling(Describable executable, InputFileChanges wrapped) {
        ErrorHandlingChangeContainer errorHandlingChangeContainer = new ErrorHandlingChangeContainer(executable, wrapped);
        return new InputFileChangesWrapper(wrapped, errorHandlingChangeContainer);
    }

    private static class InputFileChangesWrapper implements InputFileChanges {
        private final InputFileChanges inputFileChangesDelegate;
        private final ChangeContainer changeContainerDelegate;

        public InputFileChangesWrapper(InputFileChanges inputFileChangesDelegate, ChangeContainer changeContainerDelegate) {
            this.inputFileChangesDelegate = inputFileChangesDelegate;
            this.changeContainerDelegate = changeContainerDelegate;
        }

        @Override
        public boolean accept(String propertyName, ChangeVisitor visitor) {
            return inputFileChangesDelegate.accept(propertyName, visitor);
        }

        @Override
        public boolean accept(ChangeVisitor visitor) {
            return changeContainerDelegate.accept(visitor);
        }
    }

    private static class MessageCollectingChangeVisitor implements ChangeVisitor {
        private final ImmutableCollection.Builder<String> messages;
        private final int max;
        private int count;

        public MessageCollectingChangeVisitor(ImmutableCollection.Builder<String> messages, int max) {
            this.messages = messages;
            this.max = max;
        }

        @Override
        public boolean visitChange(Change change) {
            messages.add(change.getMessage());
            return ++count < max;
        }
    }
}