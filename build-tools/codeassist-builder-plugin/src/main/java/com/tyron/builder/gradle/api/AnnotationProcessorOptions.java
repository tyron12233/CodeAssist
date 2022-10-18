package com.tyron.builder.gradle.api;

import com.android.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.gradle.process.CommandLineArgumentProvider;

/** Options for configuring Java annotation processor. */
@Deprecated
public interface AnnotationProcessorOptions {

    /**
     * Annotation processors to run.
     *
     * <p>If empty, processors will be automatically discovered.
     */
    @NonNull
    List<String> getClassNames();

    /**
     * Options for the annotation processors provided via key-value pairs.
     *
     * @see #getCompilerArgumentProviders()
     */
    @NonNull
    Map<String, String> getArguments();

    /**
     * Options for the annotation processors provided via {@link CommandLineArgumentProvider}.
     *
     * @see #getArguments()
     */
    @NonNull
    List<CommandLineArgumentProvider> getCompilerArgumentProviders();
}