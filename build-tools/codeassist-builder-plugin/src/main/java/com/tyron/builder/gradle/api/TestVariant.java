package com.tyron.builder.gradle.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;

/** A Build variant and all its public data. */
@Deprecated
public interface TestVariant extends ApkVariant {

    /**
     * Returns the build variant that is tested by this variant.
     */
    @NonNull
    BaseVariant getTestedVariant();

    /**
     * Returns the task to run the tests.
     *
     * <p>Only valid for test project.
     *
     * @deprecated Use {@link #getConnectedInstrumentTestProvider()}
     */
    @Nullable
    @Deprecated
    DefaultTask getConnectedInstrumentTest();

    /**
     * Returns the {@link TaskProvider} for the task to run the tests.
     *
     * <p>Only valid for test project.
     *
     * <p>Prefer this to {@link #getConnectedInstrumentTest()} as it triggers eager configuration of
     * the task.
     */
    @Nullable
    TaskProvider<Task> getConnectedInstrumentTestProvider();

    /**
     * Returns the tasks to run the tests.
     *
     * <p>Only valid for test project.
     *
     * @deprecated Use {@link #getProviderInstrumentTestProviders()}
     */
    @NonNull
    List<? extends DefaultTask> getProviderInstrumentTests();

    /**
     * Returns the {@link TaskProvider}s for the tasks to run the tests.
     *
     * <p>Only valid for test project.
     *
     * <p>Prefer this to {@link #getProviderInstrumentTests()} as it triggers eager configuration of
     * the tasks.
     */
    @NonNull
    List<TaskProvider<Task>> getProviderInstrumentTestProviders();

}
