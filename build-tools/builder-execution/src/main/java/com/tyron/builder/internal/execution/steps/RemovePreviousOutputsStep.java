package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.OutputsCleaner;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.SnapshotUtil;
import com.tyron.builder.internal.file.TreeType;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;

/**
 * When executed non-incrementally remove previous outputs owned by the work unit.
 */
public class RemovePreviousOutputsStep<C extends InputChangesContext, R extends Result> implements Step<C, R> {

    private final Deleter deleter;
    private final OutputChangeListener outputChangeListener;
    private final Step<? super C, ? extends R> delegate;

    public RemovePreviousOutputsStep(
            Deleter deleter,
            OutputChangeListener outputChangeListener,
            Step<? super C, ? extends R> delegate
    ) {
        this.deleter = deleter;
        this.outputChangeListener = outputChangeListener;
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        if (!context.isIncrementalExecution()) {
            if (work.shouldCleanupOutputsOnNonIncrementalExecution()) {
                boolean hasOverlappingOutputs = context.getBeforeExecutionState()
                        .flatMap(BeforeExecutionState::getDetectedOverlappingOutputs)
                        .isPresent();
                if (hasOverlappingOutputs) {
                    cleanupOverlappingOutputs(context, work);
                } else {
                    cleanupExclusivelyOwnedOutputs(context, work);
                }
            }
        }
        return delegate.execute(work, context);
    }

    private void cleanupOverlappingOutputs(BeforeExecutionContext context, UnitOfWork work) {
        context.getPreviousExecutionState().ifPresent(previousOutputs -> {
            Set<File> outputDirectoriesToPreserve = new HashSet<>();
            work.visitOutputs(context.getWorkspace(), new UnitOfWork.OutputVisitor() {
                @Override
                public void visitOutputProperty(String propertyName, TreeType type, File root, FileCollection contents) {
                    switch (type) {
                        case FILE:
                            File parentFile = root.getParentFile();
                            if (parentFile != null) {
                                outputDirectoriesToPreserve.add(parentFile);
                            }
                            break;
                        case DIRECTORY:
                            outputDirectoriesToPreserve.add(root);
                            break;
                        default:
                            throw new AssertionError();
                    }
                }
            });
            OutputsCleaner cleaner = new OutputsCleaner(
                    deleter,
                    file -> true,
                    dir -> !outputDirectoriesToPreserve.contains(dir)
            );
            for (FileSystemSnapshot snapshot : previousOutputs.getOutputFilesProducedByWork().values()) {
                try {
                    // Previous outputs can be in a different place than the current outputs
                    outputChangeListener.beforeOutputChange(SnapshotUtil.rootIndex(snapshot).keySet());
                    cleaner.cleanupOutputs(snapshot);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to clean up output files for " + work.getDisplayName(), e);
                }
            }
        });
    }

    private void cleanupExclusivelyOwnedOutputs(BeforeExecutionContext context, UnitOfWork work) {
        work.visitOutputs(context.getWorkspace(), new UnitOfWork.OutputVisitor() {
            @Override
            public void visitOutputProperty(String propertyName, TreeType type, File root, FileCollection contents) {
                if (root.exists()) {
                    try {
                        switch (type) {
                            case FILE:
                                deleter.delete(root);
                                break;
                            case DIRECTORY:
                                deleter.ensureEmptyDirectory(root);
                                break;
                            default:
                                throw new AssertionError();
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                }
            }
        });
    }
}