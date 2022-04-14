package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.tasks.compile.BaseForkOptions;

/**
 * This class and its subclasses exist so that we have an isolatable instance
 * of the fork options that can be passed along with the compilation spec to a
 * worker executor.  Since {@link ProviderAwareCompilerDaemonForkOptions}
 * and its subclasses can accept user-defined {@link org.gradle.process.CommandLineArgumentProvider}
 * instances, these objects may contain references to mutable objects in the
 * Gradle model or other non-isolatable objects.
 *
 * Subclasses should be sure to collapse any {@link org.gradle.process.CommandLineArgumentProvider}
 * arguments into {@link #jvmArgs} in order to capture the user-provided
 * command line arguments.
 */
public class MinimalCompilerDaemonForkOptions extends BaseForkOptions {
    public MinimalCompilerDaemonForkOptions(BaseForkOptions forkOptions) {
        setJvmArgs(forkOptions.getJvmArgs());
        setMemoryInitialSize(forkOptions.getMemoryInitialSize());
        setMemoryMaximumSize(forkOptions.getMemoryMaximumSize());
    }
}
