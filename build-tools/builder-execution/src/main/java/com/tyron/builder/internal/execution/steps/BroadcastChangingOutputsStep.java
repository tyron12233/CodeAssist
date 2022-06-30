package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.UnitOfWork;
import com.tyron.builder.internal.file.TreeType;

import java.io.File;

public class BroadcastChangingOutputsStep<C extends WorkspaceContext, R extends Result> implements Step<C, R> {

    private final OutputChangeListener outputChangeListener;
    private final Step<? super C, ? extends R> delegate;

    public BroadcastChangingOutputsStep(
            OutputChangeListener outputChangeListener,
            Step<? super C, ? extends R> delegate
    ) {
        this.outputChangeListener = outputChangeListener;
        this.delegate = delegate;
    }

    @Override
    public R execute(UnitOfWork work, C context) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        work.visitOutputs(context.getWorkspace(), new UnitOfWork.OutputVisitor() {
            @Override
            public void visitOutputProperty(String propertyName, TreeType type, File root, FileCollection contents) {
                builder.add(root.getAbsolutePath());
            }

            @Override
            public void visitLocalState(File localStateRoot) {
                builder.add(localStateRoot.getAbsolutePath());
            }

            @Override
            public void visitDestroyable(File destroyableRoot) {
                builder.add(destroyableRoot.getAbsolutePath());
            }
        });
        outputChangeListener.beforeOutputChange(builder.build());
        return delegate.execute(work, context);
    }
}
