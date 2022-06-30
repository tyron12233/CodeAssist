package com.tyron.builder.api.tasks.compile;

import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Optional;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fork options for compilation. Only take effect if {@code fork}
 * is {@code true}.
 */
public class BaseForkOptions extends AbstractOptions {
    private static final long serialVersionUID = 0;

    private String memoryInitialSize;

    private String memoryMaximumSize;

    private List<String> jvmArgs = new ArrayList<>();

    /**
     * Returns the initial heap size for the compiler process.
     * Defaults to {@code null}, in which case the JVM's default will be used.
     */
    @Internal
    public String getMemoryInitialSize() {
        return memoryInitialSize;
    }

    /**
     * Sets the initial heap size for the compiler process.
     * Defaults to {@code null}, in which case the JVM's default will be used.
     */
    public void setMemoryInitialSize(String memoryInitialSize) {
        this.memoryInitialSize = memoryInitialSize;
    }

    /**
     * Returns the maximum heap size for the compiler process.
     * Defaults to {@code null}, in which case the JVM's default will be used.
     */
    @Internal
    public String getMemoryMaximumSize() {
        return memoryMaximumSize;
    }

    /**
     * Sets the maximum heap size for the compiler process.
     * Defaults to {@code null}, in which case the JVM's default will be used.
     */
    public void setMemoryMaximumSize(String memoryMaximumSize) {
        this.memoryMaximumSize = memoryMaximumSize;
    }

    /**
     * Returns any additional JVM arguments for the compiler process.
     * Defaults to the empty list.
     */
    @Nullable
    @Optional
    @Input
    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    /**
     * Sets any additional JVM arguments for the compiler process.
     * Defaults to the empty list. Empty or null arguments are filtered out because they cause
     * JVM Launch to fail.
     */
    public void setJvmArgs(@Nullable List<String> jvmArgs) {
        this.jvmArgs = jvmArgs == null ? null : jvmArgs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(string -> !string.isEmpty())
                .collect(Collectors.toList());
    }
}
