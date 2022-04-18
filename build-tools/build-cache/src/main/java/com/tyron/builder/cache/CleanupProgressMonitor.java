package com.tyron.builder.cache;


public interface CleanupProgressMonitor {

    void incrementDeleted();

    void incrementSkipped();

    void incrementSkipped(long amount);

    CleanupProgressMonitor NO_OP = new CleanupProgressMonitor() {
        @Override
        public void incrementDeleted() {
        }

        @Override
        public void incrementSkipped() {
        }

        @Override
        public void incrementSkipped(long amount) {
        }
    };

}