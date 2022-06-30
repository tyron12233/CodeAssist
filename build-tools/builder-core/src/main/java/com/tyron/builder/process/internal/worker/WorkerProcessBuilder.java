package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.logging.LogLevel;

import java.io.File;
import java.net.URL;
import java.util.List;

/**
 * <p>A builder which configures and creates worker processes.</p>
 *
 * <p>A worker process runs an {@link Action} instance. The given action instance is serialized across into the worker process and executed.
 * The worker action is supplied with a {@link WorkerProcessContext} which it can use to receive messages from and send messages to the server process (ie this process).
 * </p>
 *
 * <p>The server process (ie this process) can send messages to and receive message from the worker process using the methods on {@link WorkerProcess#getConnection()}.</p>
 */
public interface WorkerProcessBuilder extends WorkerProcessSettings {
    @Override
    WorkerProcessBuilder applicationClasspath(Iterable<File> files);

    @Override
    WorkerProcessBuilder applicationModulePath(Iterable<File> files);

    @Override
    WorkerProcessBuilder setBaseName(String baseName);

    @Override
    WorkerProcessBuilder setLogLevel(LogLevel logLevel);

    @Override
    WorkerProcessBuilder sharedPackages(Iterable<String> packages);

    @Override
    WorkerProcessBuilder sharedPackages(String... packages);

    Action<? super WorkerProcessContext> getWorker();

    void setImplementationClasspath(List<URL> implementationClasspath);

    void setImplementationModulePath(List<URL> implementationModulePath);

    void enableJvmMemoryInfoPublishing(boolean shouldPublish);

    /**
     * Creates the worker process. The process is not started until {@link WorkerProcess#start()} is called.
     *
     * <p>This method can be called multiple times, to create multiple worker processes.</p>
     */
    WorkerProcess build();
}