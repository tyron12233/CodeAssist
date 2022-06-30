package com.tyron.builder.process.internal.worker.child;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.CompositeStoppable;
import com.tyron.builder.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.io.ClassLoaderObjectInputStream;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.services.LoggingServiceRegistry;
import com.tyron.builder.internal.nativeintegration.services.NativeServices;
import com.tyron.builder.internal.remote.MessagingClient;
import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.internal.remote.internal.inet.MultiChoiceAddress;
import com.tyron.builder.internal.remote.internal.inet.MultiChoiceAddressSerializer;
import com.tyron.builder.internal.remote.services.MessagingServices;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.InputStreamBackedDecoder;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.scopes.Scope.Global;
import com.tyron.builder.process.internal.health.memory.DefaultJvmMemoryInfo;
import com.tyron.builder.process.internal.health.memory.DefaultMemoryManager;
import com.tyron.builder.process.internal.health.memory.DisabledOsMemoryInfo;
import com.tyron.builder.process.internal.health.memory.JvmMemoryInfo;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatusListener;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.health.memory.OsMemoryInfo;
import com.tyron.builder.process.internal.worker.WorkerJvmMemoryInfoSerializer;
import com.tyron.builder.process.internal.worker.WorkerLoggingSerializer;
import com.tyron.builder.process.internal.worker.WorkerProcessContext;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.ObjectInputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Callable;

/**
 * <p>Stage 2 of the start-up for a worker process with the application classes loaded in the system ClassLoader. Takes
 * care of deserializing and invoking the worker action.</p>
 *
 * <p> Instantiated in the implementation ClassLoader and invoked from {@link com.tyron.builder.process.internal.worker.GradleWorkerMain}.
 * See {@link ApplicationClassesInSystemClassLoaderWorkerImplementationFactory} for details.</p>
 */
public class SystemApplicationClassLoaderWorker implements Callable<Void> {
    private final DataInputStream configInputStream;

    public SystemApplicationClassLoaderWorker(DataInputStream configInputStream) {
        this.configInputStream = configInputStream;
    }

    @Override
    public Void call() throws Exception {
        if (System.getProperty("com.tyron.builder.worker.test.stuck") != null) {
            // Simulate a stuck worker. There's probably a way to inject this failure...
            Thread.sleep(30000);
            return null;
        }

        Decoder decoder = new InputStreamBackedDecoder(configInputStream);

        // Read logging config and setup logging
        int logLevel = decoder.readSmallInt();
        LoggingServiceRegistry loggingServiceRegistry = LoggingServiceRegistry.newEmbeddableLogging();
        LoggingManagerInternal loggingManager = createLoggingManager(loggingServiceRegistry).setLevelInternal(LogLevel.values()[logLevel]);

        // Read whether process info should be published
        boolean shouldPublishJvmMemoryInfo = decoder.readBoolean();

        // Read path to Gradle user home
        String gradleUserHomeDirPath = decoder.readString();
        File gradleUserHomeDir = new File(gradleUserHomeDirPath);

        // Read server address and start connecting
        MultiChoiceAddress serverAddress = new MultiChoiceAddressSerializer().read(decoder);
        NativeServices.initializeOnWorker(gradleUserHomeDir);
        DefaultServiceRegistry basicWorkerServices = new DefaultServiceRegistry(NativeServices.getInstance(), loggingServiceRegistry);
        basicWorkerServices.add(ExecutorFactory.class, new DefaultExecutorFactory());
        basicWorkerServices.addProvider(new MessagingServices());
        final WorkerServices workerServices = new WorkerServices(basicWorkerServices, gradleUserHomeDir);
        WorkerLogEventListener workerLogEventListener = new WorkerLogEventListener();
        workerServices.add(WorkerLogEventListener.class, workerLogEventListener);

        File workingDirectory = workerServices.get(WorkerDirectoryProvider.class).getWorkingDirectory();
        File errorLog = getLastResortErrorLogFile(workingDirectory);
        PrintUnrecoverableErrorToFileHandler unrecoverableErrorHandler = new PrintUnrecoverableErrorToFileHandler(errorLog);

        ObjectConnection connection = null;

        try {
            // Read serialized worker details
            final long workerId = decoder.readSmallLong();
            final String displayName = decoder.readString();
            byte[] serializedWorker = decoder.readBinary();
            Action<WorkerProcessContext> workerAction = deserializeWorker(serializedWorker);

            connection = basicWorkerServices.get(MessagingClient.class).getConnection(serverAddress);
            connection.addUnrecoverableErrorHandler(unrecoverableErrorHandler);
            configureLogging(loggingManager, connection, workerLogEventListener);
            // start logging now that the logging manager is connected
            loggingManager.start();
            if (shouldPublishJvmMemoryInfo) {
                configureWorkerJvmMemoryInfoEvents(workerServices, connection);
            }

            ActionExecutionWorker worker = new ActionExecutionWorker(workerAction);
            worker.execute(new ContextImpl(workerId, displayName, connection, workerServices));
        } finally {
            try {
                loggingManager.removeOutputEventListener(workerLogEventListener);
                CompositeStoppable.stoppable(connection, basicWorkerServices).stop();
                loggingManager.stop();
            } catch (Throwable t) {
                // We're failing while shutting down, so log whatever might have happened.
                unrecoverableErrorHandler.execute(t);
            }
        }

        return null;
    }

    private Action<WorkerProcessContext> deserializeWorker(byte[] serializedWorker) {
        Action<WorkerProcessContext> action;
        try {
            ObjectInputStream instr = new ClassLoaderObjectInputStream(new ByteArrayInputStream(serializedWorker), getClass().getClassLoader());
            @SuppressWarnings("unchecked")
            Action<WorkerProcessContext> deserializedAction = (Action<WorkerProcessContext>) instr.readObject();
            action = deserializedAction;
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
        return action;
    }

    private File getLastResortErrorLogFile(File workingDirectory) {
        return new File(workingDirectory, "worker-error-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".txt");
    }

    private static class PrintUnrecoverableErrorToFileHandler implements Action<Throwable> {
        private final File errorLog;

        private PrintUnrecoverableErrorToFileHandler(File errorLog) {
            this.errorLog = errorLog;
        }

        @Override
        public void execute(Throwable throwable) {
            try {
                final PrintStream ps = new PrintStream(errorLog);
                try {
                    ps.println("Encountered unrecoverable error:");
                    throwable.printStackTrace(ps);
                } finally {
                    ps.close();
                }
            } catch (FileNotFoundException e) {
                // ignore this, we won't be able to get any logs
            }
        }
    }

    private void configureLogging(LoggingManagerInternal loggingManager, ObjectConnection connection, WorkerLogEventListener workerLogEventListener) {
        connection.useParameterSerializers(WorkerLoggingSerializer.create());
        WorkerLoggingProtocol workerLoggingProtocol = connection.addOutgoing(WorkerLoggingProtocol.class);
        workerLogEventListener.setWorkerLoggingProtocol(workerLoggingProtocol);
        loggingManager.addOutputEventListener(workerLogEventListener);
    }

    private void configureWorkerJvmMemoryInfoEvents(WorkerServices services, ObjectConnection connection) {
        connection.useParameterSerializers(WorkerJvmMemoryInfoSerializer.create());
        final WorkerJvmMemoryInfoProtocol workerJvmMemoryInfoProtocol = connection.addOutgoing(WorkerJvmMemoryInfoProtocol.class);
        services.get(MemoryManager.class).addListener(new JvmMemoryStatusListener() {
            @Override
            public void onJvmMemoryStatus(JvmMemoryStatus jvmMemoryStatus) {
                workerJvmMemoryInfoProtocol.sendJvmMemoryStatus(jvmMemoryStatus);
            }
        });
    }

    LoggingManagerInternal createLoggingManager(LoggingServiceRegistry loggingServiceRegistry) {
        LoggingManagerInternal loggingManagerInternal = loggingServiceRegistry.newInstance(LoggingManagerInternal.class);
        loggingManagerInternal.captureSystemSources();
        return loggingManagerInternal;
    }

    private static class WorkerServices extends DefaultServiceRegistry {
        public WorkerServices(ServiceRegistry parent, final File gradleUserHomeDir) {
            super(parent);
            addProvider(new Object() {
                GradleUserHomeDirProvider createGradleUserHomeDirProvider() {
                    return new GradleUserHomeDirProvider() {
                        @Override
                        public File getGradleUserHomeDirectory() {
                            return gradleUserHomeDir;
                        }
                    };
                }
            });
        }

        DefaultListenerManager createListenerManager() {
            return new DefaultListenerManager(Global.class);
        }

        OsMemoryInfo createOsMemoryInfo() {
            return new DisabledOsMemoryInfo();
        }

        JvmMemoryInfo createJvmMemoryInfo() {
            return new DefaultJvmMemoryInfo();
        }

        MemoryManager createMemoryManager(OsMemoryInfo osMemoryInfo, JvmMemoryInfo jvmMemoryInfo, ListenerManager listenerManager, ExecutorFactory executorFactory) {
            return new DefaultMemoryManager(osMemoryInfo, jvmMemoryInfo, listenerManager, executorFactory);
        }

        WorkerDirectoryProvider createWorkerDirectoryProvider(GradleUserHomeDirProvider gradleUserHomeDirProvider) {
            return new DefaultWorkerDirectoryProvider(gradleUserHomeDirProvider);
        }
    }

    private static class ContextImpl implements WorkerProcessContext {
        private final long workerId;
        private final String displayName;
        private final ObjectConnection serverConnection;
        private final WorkerServices workerServices;

        public ContextImpl(long workerId, String displayName, ObjectConnection serverConnection, WorkerServices workerServices) {
            this.workerId = workerId;
            this.displayName = displayName;
            this.serverConnection = serverConnection;
            this.workerServices = workerServices;
        }

        @Override
        public Object getWorkerId() {
            return workerId;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public ClassLoader getApplicationClassLoader() {
            return ClassLoader.getSystemClassLoader();
        }

        @Override
        public ObjectConnection getServerConnection() {
            return serverConnection;
        }

        @Override
        public ServiceRegistry getServiceRegistry() {
            return workerServices;
        }
    }
}
