package com.tyron.builder.internal.execution.steps;

import static com.tyron.builder.util.internal.GFileUtils.mkdirs;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.file.TreeType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class CreateOutputsStep<C extends WorkspaceContext, R extends Result> implements Step<C, R> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateOutputsStep.class);

    private final Step<? super C, ? extends R> delegate;

    public CreateOutputsStep(Step<? super C, ? extends R> delegate) {
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        work.visitOutputs(context.getWorkspace(), new UnitOfWork.OutputVisitor() {
            @Override
            public void visitOutputProperty(String propertyName,
                                            TreeType type,
                                            File root,
                                            FileCollection contents) {
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
                LOGGER.debug("Ensuring directory exists for property {} at {}", name, outputRoot);
                mkdirs(outputRoot);
                break;
            case FILE:
                LOGGER.debug("Ensuring parent directory exists for property {} at {}", name,
                        outputRoot);
                mkdirs(outputRoot.getParentFile());
                break;
            default:
                throw new AssertionError();
        }
    }
}
