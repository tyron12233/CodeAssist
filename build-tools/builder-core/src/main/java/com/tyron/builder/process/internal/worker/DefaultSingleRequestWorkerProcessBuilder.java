package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.classloader.ClasspathUtil;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.operations.CurrentBuildOperationRef;
import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.process.internal.JavaExecHandleBuilder;
import com.tyron.builder.process.internal.worker.request.Receiver;
import com.tyron.builder.process.internal.worker.request.Request;
import com.tyron.builder.process.internal.worker.request.RequestArgumentSerializers;
import com.tyron.builder.process.internal.worker.request.RequestProtocol;
import com.tyron.builder.process.internal.worker.request.RequestSerializerRegistry;
import com.tyron.builder.process.internal.worker.request.ResponseProtocol;
import com.tyron.builder.process.internal.worker.request.WorkerAction;

import java.io.File;
import java.util.Set;

class DefaultSingleRequestWorkerProcessBuilder<IN, OUT> implements SingleRequestWorkerProcessBuilder<IN, OUT> {
    private final Class<?> workerImplementation;
    private final DefaultWorkerProcessBuilder builder;
    private final RequestArgumentSerializers argumentSerializers = new RequestArgumentSerializers();
    private final OutputEventListener outputEventListener;

    public DefaultSingleRequestWorkerProcessBuilder(Class<?> workerImplementation, DefaultWorkerProcessBuilder builder, OutputEventListener outputEventListener) {
        this.workerImplementation = workerImplementation;
        this.builder = builder;
        this.outputEventListener = outputEventListener;
        builder.worker(new WorkerAction(workerImplementation));
        builder.setImplementationClasspath(ClasspathUtil.getClasspath(workerImplementation.getClassLoader()).getAsURLs());
    }

    @Override
    public WorkerProcessSettings setBaseName(String baseName) {
        builder.setBaseName(baseName);
        return this;
    }

    @Override
    public String getBaseName() {
        return builder.getBaseName();
    }

    @Override
    public WorkerProcessSettings applicationClasspath(Iterable<File> files) {
        builder.applicationClasspath(files);
        return this;
    }

    @Override
    public Set<File> getApplicationClasspath() {
        return builder.getApplicationClasspath();
    }

    @Override
    public WorkerProcessSettings applicationModulePath(Iterable<File> files) {
        builder.applicationModulePath(files);
        return this;
    }

    @Override
    public Set<File> getApplicationModulePath() {
        return builder.getApplicationModulePath();
    }

    @Override
    public WorkerProcessSettings sharedPackages(String... packages) {
        builder.sharedPackages(packages);
        return this;
    }

    @Override
    public WorkerProcessSettings sharedPackages(Iterable<String> packages) {
        builder.sharedPackages(packages);
        return this;
    }

    @Override
    public Set<String> getSharedPackages() {
        return builder.getSharedPackages();
    }

    @Override
    public JavaExecHandleBuilder getJavaCommand() {
        return builder.getJavaCommand();
    }

    @Override
    public LogLevel getLogLevel() {
        return builder.getLogLevel();
    }

    @Override
    public WorkerProcessSettings setLogLevel(LogLevel logLevel) {
        builder.setLogLevel(logLevel);
        return this;
    }

    @Override
    public RequestHandler<IN, OUT> build() {
        return new RequestHandler<IN, OUT>() {
            @Override
            public OUT run(IN request) {
                Receiver receiver = new Receiver(getBaseName(), outputEventListener);
                try {
                    WorkerProcess workerProcess = builder.build();
                    workerProcess.start();
                    ObjectConnection connection = workerProcess.getConnection();
                    RequestProtocol requestProtocol = connection.addOutgoing(RequestProtocol.class);
                    connection.addIncoming(ResponseProtocol.class, receiver);
                    connection.useJavaSerializationForParameters(workerImplementation.getClassLoader());
                    connection.useParameterSerializers(RequestSerializerRegistry.create(workerImplementation.getClassLoader(), argumentSerializers));
                    connection.connect();
                    // TODO(ew): inject BuildOperationIdentifierRegistry instead of static use
                    requestProtocol.runThenStop(new Request(request, CurrentBuildOperationRef.instance().get()));
                    boolean hasResult = receiver.awaitNextResult();
                    workerProcess.waitForStop();
                    if (!hasResult) {
                        // Reached the end of input, worker has exited without failing
                        throw new IllegalStateException(String.format("No response was received from %s but the worker process has finished.", getBaseName()));
                    }
                } catch (Exception e) {
                    throw WorkerProcessException.runFailed(getBaseName(), e);
                }
                return Cast.uncheckedNonnullCast(receiver.getNextResult());
            }
        };
    }
}
