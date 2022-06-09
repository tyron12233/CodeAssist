package com.tyron.builder.api.tasks.compile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.api.tasks.Nested;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.process.CommandLineArgumentProvider;
import com.tyron.builder.util.internal.CollectionUtils;

import java.util.List;

/**
 * Fork options for compilation that can accept user-defined {@link CommandLineArgumentProvider} objects.
 *
 * Only take effect if {@code fork} is {@code true}.
 *
 * @since 7.1
 */
//@Incubating
public class ProviderAwareCompilerDaemonForkOptions extends BaseForkOptions {

    private final List<CommandLineArgumentProvider> jvmArgumentProviders = Lists.newArrayList();

    /**
     * Returns any additional JVM argument providers for the compiler process.
     *
     */
    @Optional
    @Nested
    public List<CommandLineArgumentProvider> getJvmArgumentProviders() {
        return jvmArgumentProviders;
    }

    /**
     * Returns the full set of arguments to use to launch the JVM for the compiler process. This includes arguments to define
     * system properties, the minimum/maximum heap size, and the bootstrap classpath.
     *
     * @return The arguments. Returns an empty list if there are no arguments.
     */
    @Internal
    public List<String> getAllJvmArgs() {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        builder.addAll(CollectionUtils.stringize(getJvmArgs()));
        for (CommandLineArgumentProvider argumentProvider : getJvmArgumentProviders()) {
            builder.addAll(argumentProvider.asArguments());
        }
        return builder.build();
    }
}

