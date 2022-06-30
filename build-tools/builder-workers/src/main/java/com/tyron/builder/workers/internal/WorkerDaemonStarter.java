package com.tyron.builder.workers.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.logging.LoggingManager;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;
import com.tyron.builder.process.internal.JavaExecHandleBuilder;
import com.tyron.builder.process.internal.worker.MultiRequestClient;
import com.tyron.builder.process.internal.worker.MultiRequestWorkerProcessBuilder;
import com.tyron.builder.process.internal.worker.WorkerProcess;
import com.tyron.builder.process.internal.worker.WorkerProcessFactory;
import com.tyron.builder.util.internal.CollectionUtils;

import java.io.File;
import java.net.URISyntaxException;

public class WorkerDaemonStarter {
    private final static Logger LOG = Logging.getLogger(WorkerDaemonStarter.class);
    private final WorkerProcessFactory workerDaemonProcessFactory;
    private final LoggingManager loggingManager;
    private final ClassPathRegistry classPathRegistry;
    private final ActionExecutionSpecFactory actionExecutionSpecFactory;

    public WorkerDaemonStarter(WorkerProcessFactory workerDaemonProcessFactory, LoggingManager loggingManager, ClassPathRegistry classPathRegistry, ActionExecutionSpecFactory actionExecutionSpecFactory) {
        this.workerDaemonProcessFactory = workerDaemonProcessFactory;
        this.loggingManager = loggingManager;
        this.classPathRegistry = classPathRegistry;
        this.actionExecutionSpecFactory = actionExecutionSpecFactory;
    }

    public WorkerDaemonClient startDaemon(DaemonForkOptions forkOptions, Action<WorkerProcess> cleanupAction) {
        LOG.debug("Starting Gradle worker daemon with fork options {}.", forkOptions);
        Timer clock = Time.startTimer();
        MultiRequestWorkerProcessBuilder<TransportableActionExecutionSpec, DefaultWorkResult> builder = workerDaemonProcessFactory.multiRequestWorker(WorkerDaemonServer.class);
        builder.setBaseName("Gradle Worker Daemon");
        builder.setLogLevel(loggingManager.getLevel()); // NOTE: might make sense to respect per-compile-task log level
        builder.sharedPackages("com.tyron.builder", "javax.inject");
        if (forkOptions.getClassLoaderStructure() instanceof FlatClassLoaderStructure) {
            FlatClassLoaderStructure flatClassLoaderStructure = (FlatClassLoaderStructure) forkOptions.getClassLoaderStructure();
            builder.applicationClasspath(classPathRegistry.getClassPath("MINIMUM_WORKER_RUNTIME").getAsFiles());
            builder.useApplicationClassloaderOnly();
            builder.applicationClasspath(toFiles(flatClassLoaderStructure.getSpec()));
        } else {
            builder.applicationClasspath(classPathRegistry.getClassPath("CORE_WORKER_RUNTIME").getAsFiles());
        }
        builder.onProcessFailure(cleanupAction);
        JavaExecHandleBuilder javaCommand = builder.getJavaCommand();
        forkOptions.getJavaForkOptions().copyTo(javaCommand);
        builder.registerArgumentSerializer(TransportableActionExecutionSpec.class, new TransportableActionExecutionSpecSerializer());
        MultiRequestClient<TransportableActionExecutionSpec, DefaultWorkResult> workerDaemonProcess = builder.build();
        WorkerProcess workerProcess = workerDaemonProcess.start();

        WorkerDaemonClient client = new WorkerDaemonClient(forkOptions, workerDaemonProcess, workerProcess, loggingManager.getLevel(), actionExecutionSpecFactory);

        LOG.info("Started Gradle worker daemon ({}) with fork options {}.", clock.getElapsed(), forkOptions);

        return client;
    }

    private static Iterable<File> toFiles(VisitableURLClassLoader.Spec spec) {
        return CollectionUtils.collect(spec.getClasspath(), url -> {
            try {
                return new File(url.toURI());
            } catch (URISyntaxException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        });
    }
}
