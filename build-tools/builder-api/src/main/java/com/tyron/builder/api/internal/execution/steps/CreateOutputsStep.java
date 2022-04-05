package com.tyron.builder.api.internal.execution.steps;

import static com.tyron.builder.api.util.GFileUtils.mkdirs;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.execution.UnitOfWork;
import com.tyron.builder.api.internal.tasks.properties.TreeType;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Logger;

public class CreateOutputsStep<C extends WorkspaceContext, R extends Result> implements Step<C, R> {
    private static final Logger LOGGER = Logger.getLogger(CreateOutputsStep.class.getSimpleName());

    private final Step<? super C, ? extends R> delegate;

    public CreateOutputsStep(Step<? super C, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        work.visitOutputs(context.getWorkspace(), new UnitOfWork.OutputVisitor() {
            @Override
            public void visitOutputProperty(String propertyName, TreeType type, File root, FileCollection contents) {
                ensureOutput(propertyName, root, type);
            }

            @Override
            public void visitLocalState(File localStateRoot) {
                ensureOutput("local state", localStateRoot, TreeType.FILE);
            }
        });
        return delegate.execute(work, context);
    }

    private static void ensureOutput(String name, File outputRoot, TreeType type) {
        switch (type) {
            case DIRECTORY:
                LOGGER.info("Ensuring directory exists for property " + name + " at " + outputRoot);
                mkdirs(outputRoot);
                break;
            case FILE:
                LOGGER.info("Ensuring parent directory exists for property " + name + " at " + outputRoot);
                mkdirs(outputRoot.getParentFile());
                break;
            default:
                throw new AssertionError();
        }
    }
}