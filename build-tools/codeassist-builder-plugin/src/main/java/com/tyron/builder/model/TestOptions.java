package com.tyron.builder.model;

/** Test options for running tests - e.g. instrumented or not. */
public interface TestOptions {
    enum Execution {
        /** On device orchestration is not used in this case. */
        HOST,
        /** On device orchestration is used. */
        ANDROID_TEST_ORCHESTRATOR,
        /** On device orchestration is used, with androidx class names. */
        ANDROIDX_TEST_ORCHESTRATOR,
    }

    boolean getAnimationsDisabled();

    Execution getExecution();
}