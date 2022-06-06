package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.process.internal.JavaExecHandleBuilder;

import java.io.File;
import java.util.Set;

/**
 * <p>Settings common to all worker processes.</p>
 *
 * <p>A worker process runs some action in a child process launched by this processes.</p>
 *
 * <p>A worker process can optionally specify an application classpath. The classes of this classpath are loaded into an isolated ClassLoader, which is made visible to the worker action ClassLoader.
 * Only the packages specified in the set of shared packages are visible to the worker action ClassLoader.</p>
 */
public interface WorkerProcessSettings {
    WorkerProcessSettings setBaseName(String baseName);

    String getBaseName();

    WorkerProcessSettings applicationClasspath(Iterable<File> files);

    Set<File> getApplicationClasspath();

    WorkerProcessSettings applicationModulePath(Iterable<File> files);

    Set<File> getApplicationModulePath();

    WorkerProcessSettings sharedPackages(String... packages);

    WorkerProcessSettings sharedPackages(Iterable<String> packages);

    Set<String> getSharedPackages();

    JavaExecHandleBuilder getJavaCommand();

    LogLevel getLogLevel();

    WorkerProcessSettings setLogLevel(LogLevel logLevel);
}
