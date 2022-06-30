package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.ClassPathRegistry;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.logging.LoggingManager;
import com.tyron.builder.internal.classloader.ClasspathUtil;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.jvm.inspection.JvmVersionDetector;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.remote.MessagingServer;
import com.tyron.builder.process.internal.JavaExecHandleFactory;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.worker.child.ApplicationClassesInSystemClassLoaderWorkerImplementationFactory;

import java.io.File;

public class DefaultWorkerProcessFactory implements WorkerProcessFactory {

    private final LoggingManager loggingManager;
    private final MessagingServer server;
    private final IdGenerator<Long> idGenerator;
    private final File gradleUserHomeDir;
    private final JavaExecHandleFactory execHandleFactory;
    private final OutputEventListener outputEventListener;
    private final ApplicationClassesInSystemClassLoaderWorkerImplementationFactory workerImplementationFactory;
    private final MemoryManager memoryManager;
    private int connectTimeoutSeconds = 120;

    public DefaultWorkerProcessFactory(LoggingManager loggingManager, MessagingServer server, ClassPathRegistry classPathRegistry, IdGenerator<Long> idGenerator,
                                       File gradleUserHomeDir, TemporaryFileProvider temporaryFileProvider, JavaExecHandleFactory execHandleFactory,
                                       JvmVersionDetector jvmVersionDetector, OutputEventListener outputEventListener, MemoryManager memoryManager) {
        this.loggingManager = loggingManager;
        this.server = server;
        this.idGenerator = idGenerator;
        this.gradleUserHomeDir = gradleUserHomeDir;
        this.execHandleFactory = execHandleFactory;
        this.outputEventListener = outputEventListener;
        this.workerImplementationFactory = new ApplicationClassesInSystemClassLoaderWorkerImplementationFactory(classPathRegistry, temporaryFileProvider, jvmVersionDetector, gradleUserHomeDir);
        this.memoryManager = memoryManager;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    @Override
    public WorkerProcessBuilder create(Action<? super WorkerProcessContext> workerAction) {
        DefaultWorkerProcessBuilder builder = newWorkerProcessBuilder();
        builder.worker(workerAction);
        builder.setImplementationClasspath(ClasspathUtil.getClasspath(workerAction.getClass().getClassLoader()).getAsURLs());
        return builder;
    }

    @Override
    public <IN, OUT> SingleRequestWorkerProcessBuilder<IN, OUT> singleRequestWorker(Class<? extends RequestHandler<? super IN, ? extends OUT>> workerImplementation) {
        return new DefaultSingleRequestWorkerProcessBuilder<IN, OUT>(workerImplementation, newWorkerProcessBuilder(), outputEventListener);
    }

    @Override
    public <IN, OUT> MultiRequestWorkerProcessBuilder<IN, OUT> multiRequestWorker(Class<? extends RequestHandler<? super IN, ? extends OUT>> workerImplementation) {
        return new DefaultMultiRequestWorkerProcessBuilder<IN, OUT>(workerImplementation, newWorkerProcessBuilder(), outputEventListener);
    }

    private DefaultWorkerProcessBuilder newWorkerProcessBuilder() {
        DefaultWorkerProcessBuilder builder = new DefaultWorkerProcessBuilder(execHandleFactory, server, idGenerator, workerImplementationFactory, outputEventListener, memoryManager);
        builder.setLogLevel(loggingManager.getLevel());
        builder.setGradleUserHomeDir(gradleUserHomeDir);
        builder.setConnectTimeoutSeconds(connectTimeoutSeconds);
        return builder;
    }
}
