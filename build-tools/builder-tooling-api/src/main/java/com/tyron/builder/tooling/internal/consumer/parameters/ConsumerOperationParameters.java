/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tyron.builder.tooling.internal.consumer.parameters;

import com.google.common.collect.Lists;
import com.tyron.builder.api.BuildException;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.tooling.CancellationToken;
import com.tyron.builder.tooling.events.OperationType;
import com.tyron.builder.tooling.events.ProgressListener;
import com.tyron.builder.tooling.internal.adapter.ProtocolToModelAdapter;
import com.tyron.builder.tooling.internal.consumer.CancellationTokenInternal;
import com.tyron.builder.tooling.internal.consumer.ConnectionParameters;
import com.tyron.builder.tooling.internal.gradle.TaskListingLaunchable;
import com.tyron.builder.tooling.internal.protocol.BuildParameters;
import com.tyron.builder.tooling.internal.protocol.InternalLaunchable;
import com.tyron.builder.tooling.internal.protocol.ProgressListenerVersion1;
import com.tyron.builder.tooling.model.Launchable;
import com.tyron.builder.tooling.model.Task;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * This is used via reflection from {@code ProviderOperationParameters}.
 */
@SuppressWarnings("deprecation")
public class ConsumerOperationParameters implements com.tyron.builder.tooling.internal.protocol.BuildOperationParametersVersion1, com.tyron.builder.tooling.internal.protocol.BuildParametersVersion1, BuildParameters {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<com.tyron.builder.tooling.ProgressListener> legacyProgressListeners = new ArrayList<com.tyron.builder.tooling.ProgressListener>();
        private final Map<OperationType, List<ProgressListener>> progressListeners = new EnumMap<OperationType, List<ProgressListener>>(OperationType.class);
        private String entryPoint;
        private CancellationToken cancellationToken;
        private ConnectionParameters parameters;
        private OutputStream stdout;
        private OutputStream stderr;
        private Boolean colorOutput;
        private InputStream stdin;
        private File javaHome;
        private List<String> jvmArguments;
        private Map<String, String> envVariables;
        private List<String> arguments;
        private List<String> tasks;
        private List<InternalLaunchable> launchables;
        private ClassPath injectedPluginClasspath = ClassPath.EMPTY;

        private Builder() {
        }

        public Builder setEntryPoint(String entryPoint) {
            this.entryPoint = entryPoint;
            return this;
        }

        public Builder setParameters(ConnectionParameters parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder setStdout(OutputStream stdout) {
            this.stdout = stdout;
            return this;
        }

        public Builder setStderr(OutputStream stderr) {
            this.stderr = stderr;
            return this;
        }

        public Builder setColorOutput(Boolean colorOutput) {
            this.colorOutput = colorOutput;
            return this;
        }

        public Builder setStdin(InputStream stdin) {
            this.stdin = stdin;
            return this;
        }

        public Builder setJavaHome(File javaHome) {
            validateJavaHome(javaHome);
            this.javaHome = javaHome;
            return this;
        }

        public Builder setJvmArguments(List<String> jvmArguments) {
            this.jvmArguments = jvmArguments;
            return this;
        }

        public Builder addJvmArguments(List<String> jvmArguments) {
            this.jvmArguments = concat(this.jvmArguments, jvmArguments);
            return this;
        }

        public Builder setArguments(List<String> arguments) {
            this.arguments = arguments;
            return this;
        }

        public Builder addArguments(List<String> arguments) {
            this.arguments = concat(this.arguments, arguments);
            return this;
        }

        private static List<String> concat(List<String> first, List<String> second) {
            List<String> result = new ArrayList<String>();
            if (first  != null) {
                result.addAll(first);
            }
            if (second != null) {
                result.addAll(second);
            }
            return result;
        }

        public Builder setEnvironmentVariables(Map<String, String> envVariables) {
            this.envVariables = envVariables;
            return this;
        }

        public Builder setTasks(List<String> tasks) {
            this.tasks = tasks;
            return this;
        }

        public Builder setLaunchables(Iterable<? extends Launchable> launchables) {
            Set<String> taskPaths = new LinkedHashSet<String>();
            List<InternalLaunchable> launchablesParams = Lists.newArrayList();
            for (Launchable launchable : launchables) {
                Object original = new ProtocolToModelAdapter().unpack(launchable);
                if (original instanceof InternalLaunchable) {
                    // A launchable created by the provider - just hand it back
                    launchablesParams.add((InternalLaunchable) original);
                } else if (original instanceof TaskListingLaunchable) {
                    // A launchable synthesized by the consumer - unpack it into a set of task names
                    taskPaths.addAll(((TaskListingLaunchable) original).getTaskNames());
                } else if (launchable instanceof Task) {
                    // A task created by a provider that does not understand launchables
                    taskPaths.add(((Task) launchable).getPath());
                } else {
                    throw new BuildException("Only Task or TaskSelector instances are supported: "
                                             + launchable.getClass());
                }
            }
            // Tasks are ignored by providers if launchables is not null
            this.launchables = launchablesParams.isEmpty() ? null : launchablesParams;
            tasks = Lists.newArrayList(taskPaths);
            return this;
        }

        public Builder setInjectedPluginClasspath(ClassPath classPath) {
            this.injectedPluginClasspath = classPath;
            return this;
        }

        public void addProgressListener(com.tyron.builder.tooling.ProgressListener listener) {
            legacyProgressListeners.add(listener);
        }

        public void addProgressListener(ProgressListener listener, Set<OperationType> eventTypes) {
            for (OperationType type : eventTypes) {
                List<ProgressListener> listeners = this.progressListeners.computeIfAbsent(type, new Function<OperationType, List<ProgressListener>>() {
                    @Override
                    public List<ProgressListener> apply(OperationType operationType) {
                        return new ArrayList<ProgressListener>();
                    }
                });
                listeners.add(listener);
            }
        }

        public void setCancellationToken(CancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
        }

        public ConsumerOperationParameters build() {
            if (entryPoint == null) {
                throw new IllegalStateException("No entry point specified.");
            }

            return new ConsumerOperationParameters(entryPoint, parameters, stdout, stderr, colorOutput, stdin, javaHome, jvmArguments, envVariables, arguments, tasks, launchables, injectedPluginClasspath,
                legacyProgressListeners, progressListeners, cancellationToken);
        }

        public void copyFrom(ConsumerOperationParameters operationParameters) {
            tasks = operationParameters.tasks;
            launchables = operationParameters.launchables;
            cancellationToken = operationParameters.cancellationToken;
            legacyProgressListeners.addAll(operationParameters.legacyProgressListeners);
            progressListeners.putAll(operationParameters.progressListeners);
            arguments = operationParameters.arguments;
            jvmArguments = operationParameters.jvmArguments;
            envVariables = operationParameters.envVariables;
            stdout = operationParameters.stdout;
            stderr = operationParameters.stderr;
            stdin = operationParameters.stdin;
            colorOutput = operationParameters.colorOutput;
            javaHome = operationParameters.javaHome;
            injectedPluginClasspath = operationParameters.injectedPluginClasspath;
        }
    }

    private final String entryPointName;
    private final ProgressListenerAdapter progressListener;
    private final FailsafeBuildProgressListenerAdapter buildProgressListener;
    private final CancellationToken cancellationToken;
    private final ConnectionParameters parameters;
    private final long startTime = System.currentTimeMillis();

    private final OutputStream stdout;
    private final OutputStream stderr;
    private final Boolean colorOutput;
    private final InputStream stdin;

    private final File javaHome;
    private final List<String> jvmArguments;
    private final Map<String, String> envVariables;
    private final List<String> arguments;
    private final List<String> tasks;
    private final List<InternalLaunchable> launchables;
    private final ClassPath injectedPluginClasspath;

    private final List<com.tyron.builder.tooling.ProgressListener> legacyProgressListeners;
    private final Map<OperationType, List<ProgressListener>> progressListeners;

    private ConsumerOperationParameters(String entryPointName, ConnectionParameters parameters, OutputStream stdout, OutputStream stderr, Boolean colorOutput, InputStream stdin,
                                        File javaHome, List<String> jvmArguments,  Map<String, String> envVariables, List<String> arguments, List<String> tasks, List<InternalLaunchable> launchables, ClassPath injectedPluginClasspath,
                                        List<com.tyron.builder.tooling.ProgressListener> legacyProgressListeners, Map<OperationType, List<ProgressListener>> progressListeners, CancellationToken cancellationToken) {
        this.entryPointName = entryPointName;
        this.parameters = parameters;
        this.stdout = stdout;
        this.stderr = stderr;
        this.colorOutput = colorOutput;
        this.stdin = stdin;
        this.javaHome = javaHome;
        this.jvmArguments = jvmArguments;
        this.envVariables = envVariables;
        this.arguments = arguments;
        this.tasks = tasks;
        this.launchables = launchables;
        this.injectedPluginClasspath = injectedPluginClasspath;
        this.cancellationToken = cancellationToken;
        this.legacyProgressListeners = legacyProgressListeners;
        this.progressListeners = progressListeners;

        // create the listener adapters right when the ConsumerOperationParameters are instantiated but no earlier,
        // this ensures that when multiple requests are issued that are built from the same builder, such requests do not share any state kept in the listener adapters
        // e.g. if the listener adapters do per-request caching, such caching must not leak between different requests built from the same builder
        this.progressListener = new ProgressListenerAdapter(this.legacyProgressListeners);
        this.buildProgressListener = new FailsafeBuildProgressListenerAdapter(new BuildProgressListenerAdapter(this.progressListeners));
    }

    private static void validateJavaHome(File javaHome) {
        if (javaHome == null) {
            return;
        }
        if (!javaHome.isDirectory()) {
            throw new IllegalArgumentException("Supplied javaHome is not a valid folder. You supplied: " + javaHome);
        }
    }

    public String getEntryPointName() {
        return entryPointName;
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public boolean getVerboseLogging() {
        return parameters.getVerboseLogging();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public File getGradleUserHomeDir() {
        return parameters.getGradleUserHomeDir();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public File getProjectDir() {
        return parameters.getProjectDir();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public Boolean isSearchUpwards() {
        return parameters.isSearchUpwards();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public Boolean isEmbedded() {
        return parameters.isEmbedded();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public TimeUnit getDaemonMaxIdleTimeUnits() {
        return parameters.getDaemonMaxIdleTimeUnits();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public Integer getDaemonMaxIdleTimeValue() {
        return parameters.getDaemonMaxIdleTimeValue();
    }

    /**
     * @since 2.2-rc-1
     */
    public File getDaemonBaseDir() {
        return parameters.getDaemonBaseDir();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public OutputStream getStandardOutput() {
        return stdout;
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public OutputStream getStandardError() {
        return stderr;
    }

    /**
     * @since 2.3-rc-1
     */
    public Boolean isColorOutput() {
        return colorOutput;
    }

    @Override
    public InputStream getStandardInput() {
        return stdin;
    }

    public File getJavaHome() {
        return javaHome;
    }

    public List<String> getJvmArguments() {
        return jvmArguments;
    }

    public Map<String, String> getEnvironmentVariables() {
        return envVariables;
    }

    public List<String> getArguments() {
        return arguments;
    }

    @Override
    public List<String> getTasks() {
        return tasks;
    }

    /**
     * @since 1.12-rc-1
     */
    public List<InternalLaunchable> getLaunchables() {
        return launchables;
    }

    /**
     * @since 2.8-rc-1
     */
    public List<File> getInjectedPluginClasspath() {
        return injectedPluginClasspath.getAsFiles();
    }

    /**
     * @since 1.0-milestone-3
     */
    @Override
    public ProgressListenerVersion1 getProgressListener() {
        return progressListener;
    }

    /**
     * @since 2.4-rc-1
     */
    public FailsafeBuildProgressListenerAdapter getBuildProgressListener() {
        return buildProgressListener;
    }

    public BuildCancellationToken getCancellationToken() {
        return ((CancellationTokenInternal) cancellationToken).getToken();
    }

}
