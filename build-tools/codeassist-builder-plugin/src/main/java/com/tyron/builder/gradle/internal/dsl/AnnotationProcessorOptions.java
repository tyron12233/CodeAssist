package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.google.common.base.MoreObjects;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.gradle.process.CommandLineArgumentProvider;

/** Options for configuring Java annotation processors. */
public abstract class AnnotationProcessorOptions
        implements com.tyron.builder.gradle.api.AnnotationProcessorOptions,
                com.tyron.builder.api.dsl.AnnotationProcessorOptions {

    public abstract void setClassNames(@NonNull List<String> classNames);

    @Override
    public void className(@NonNull String className) {
        getClassNames().add(className);
    }

    public void classNames(Collection<String> className) {
        getClassNames().addAll(className);
    }

    @Override
    public void classNames(@NonNull String... classNames) {
        getClassNames().addAll(Arrays.asList(classNames));
    }

    public abstract void setArguments(@NonNull Map<String, String> arguments);

    @Override
    public void argument(@NonNull String key, @NonNull String value) {
        getArguments().put(key, value);
    }

    @Override
    public void arguments(@NonNull Map<String, String> arguments) {
        getArguments().putAll(arguments);
    }

    public abstract void setCompilerArgumentProviders(
            @NonNull List<CommandLineArgumentProvider> compilerArgumentProviders);

    @Override
    public void compilerArgumentProvider(
            @NonNull CommandLineArgumentProvider compilerArgumentProvider) {
        getCompilerArgumentProviders().add(compilerArgumentProvider);
    }

    public void compilerArgumentProviders(
            @NonNull List<CommandLineArgumentProvider> compilerArgumentProviders) {
        getCompilerArgumentProviders().addAll(compilerArgumentProviders);
    }

    @Override
    public void compilerArgumentProviders(
            @NonNull CommandLineArgumentProvider... compilerArgumentProviders) {
        getCompilerArgumentProviders().addAll(Arrays.asList(compilerArgumentProviders));
    }

    public void _initWith(com.tyron.builder.gradle.api.AnnotationProcessorOptions aptOptions) {
        setClassNames(aptOptions.getClassNames());
        setArguments(aptOptions.getArguments());
        setCompilerArgumentProviders(aptOptions.getCompilerArgumentProviders());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("classNames", getClassNames())
                .add("arguments", getArguments())
                .add("compilerArgumentProviders", getCompilerArgumentProviders())
                .toString();
    }
}