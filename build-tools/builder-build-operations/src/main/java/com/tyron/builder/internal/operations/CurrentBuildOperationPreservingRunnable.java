package com.tyron.builder.internal.operations;

public class CurrentBuildOperationPreservingRunnable implements Runnable {

    private final Runnable delegate;
    private final CurrentBuildOperationRef ref;
    private final BuildOperationRef buildOperation;

    public CurrentBuildOperationPreservingRunnable(Runnable delegate) {
        this(delegate, CurrentBuildOperationRef.instance());
    }

    CurrentBuildOperationPreservingRunnable(Runnable delegate, CurrentBuildOperationRef ref) {
        this.delegate = delegate;
        this.ref = ref;
        this.buildOperation = ref.get();
    }

    @Override
    public void run() {
        if (buildOperation == null) {
            delegate.run();
        } else {
            ref.set(buildOperation);
            try {
                delegate.run();
            } finally {
                ref.clear();
            }
        }
    }
}