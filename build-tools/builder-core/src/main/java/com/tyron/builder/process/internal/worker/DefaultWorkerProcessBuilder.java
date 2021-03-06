package com.tyron.builder.process.internal.worker;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.internal.id.IdGenerator;
import com.tyron.builder.internal.jvm.Jvm;
import com.tyron.builder.internal.logging.events.OutputEventListener;
import com.tyron.builder.internal.remote.Address;
import com.tyron.builder.internal.remote.ConnectionAcceptor;
import com.tyron.builder.internal.remote.MessagingServer;
import com.tyron.builder.internal.remote.ObjectConnection;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.internal.ExecHandle;
import com.tyron.builder.process.internal.JavaExecHandleBuilder;
import com.tyron.builder.process.internal.JavaExecHandleFactory;
import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;
import com.tyron.builder.process.internal.health.memory.MemoryAmount;
import com.tyron.builder.process.internal.health.memory.MemoryManager;
import com.tyron.builder.process.internal.worker.child.ApplicationClassesInSystemClassLoaderWorkerImplementationFactory;
import com.tyron.builder.process.internal.worker.child.WorkerJvmMemoryInfoProtocol;
import com.tyron.builder.process.internal.worker.child.WorkerLoggingProtocol;
import com.tyron.builder.util.internal.GUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class DefaultWorkerProcessBuilder implements WorkerProcessBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultWorkerProcessBuilder.class);
    private final MessagingServer server;
    private final IdGenerator<Long> idGenerator;
    private final ApplicationClassesInSystemClassLoaderWorkerImplementationFactory workerImplementationFactory;
    private final OutputEventListener outputEventListener;
    private final JavaExecHandleBuilder javaCommand;
    private final Set<String> packages = new HashSet<>();
    private final Set<File> applicationClasspath = new LinkedHashSet<>();
    private final Set<File> applicationModulePath = new LinkedHashSet<>();

    private final MemoryManager memoryManager;
    private Action<? super WorkerProcessContext> action;
    private LogLevel logLevel = LogLevel.LIFECYCLE;
    private String baseName = "Gradle Worker";
    private File gradleUserHomeDir;
    private int connectTimeoutSeconds;
    private List<URL> implementationClassPath;
    private List<URL> implementationModulePath;
    private boolean shouldPublishJvmMemoryInfo;

    DefaultWorkerProcessBuilder(JavaExecHandleFactory execHandleFactory, MessagingServer server, IdGenerator<Long> idGenerator, ApplicationClassesInSystemClassLoaderWorkerImplementationFactory workerImplementationFactory, OutputEventListener outputEventListener, MemoryManager memoryManager) {
        this.javaCommand = execHandleFactory.newJavaExec();
        this.javaCommand.setExecutable(Jvm.current().getJavaExecutable());
        this.server = server;
        this.idGenerator = idGenerator;
        this.workerImplementationFactory = workerImplementationFactory;
        this.outputEventListener = outputEventListener;
        this.memoryManager = memoryManager;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    @Override
    public WorkerProcessBuilder setBaseName(String baseName) {
        this.baseName = baseName;
        return this;
    }

    @Override
    public String getBaseName() {
        return baseName;
    }

    @Override
    public WorkerProcessBuilder applicationClasspath(Iterable<File> files) {
        for (File file : files) {
            if (file == null) {
                throw new IllegalArgumentException("Illegal null value provided in this collection: " + files);
            }
            if (isEntryValid(file)) {
                applicationClasspath.add(file);
            }
        }
        return this;
    }

    private boolean isEntryValid(File file) {
        return file.exists() || ("*".equals(file.getName()) && file.getParentFile() != null && file.getParentFile().exists());
    }

    @Override
    public Set<File> getApplicationClasspath() {
        return applicationClasspath;
    }

    @Override
    public WorkerProcessBuilder applicationModulePath(Iterable<File> files) {
        GUtil.addToCollection(applicationModulePath, files);
        return this;
    }

    @Override
    public Set<File> getApplicationModulePath() {
        return applicationModulePath;
    }

    @Override
    public WorkerProcessBuilder sharedPackages(String... packages) {
        sharedPackages(Arrays.asList(packages));
        return this;
    }

    @Override
    public WorkerProcessBuilder sharedPackages(Iterable<String> packages) {
        GUtil.addToCollection(this.packages, packages);
        return this;
    }

    @Override
    public Set<String> getSharedPackages() {
        return packages;
    }

    public WorkerProcessBuilder worker(Action<? super WorkerProcessContext> action) {
        this.action = action;
        return this;
    }

    @Override
    public Action<? super WorkerProcessContext> getWorker() {
        return action;
    }

    @Override
    public JavaExecHandleBuilder getJavaCommand() {
        return javaCommand;
    }

    @Override
    public LogLevel getLogLevel() {
        return logLevel;
    }

    @Override
    public WorkerProcessBuilder setLogLevel(LogLevel logLevel) {
        this.logLevel = logLevel;
        return this;
    }

    public File getGradleUserHomeDir() {
        return gradleUserHomeDir;
    }

    public void setGradleUserHomeDir(File gradleUserHomeDir) {
        this.gradleUserHomeDir = gradleUserHomeDir;
    }

    @Override
    public void setImplementationClasspath(List<URL> implementationClassPath) {
        this.implementationClassPath = implementationClassPath;
    }

    @Override
    public void setImplementationModulePath(List<URL> implementationModulePath) {
        this.implementationModulePath = implementationModulePath;
    }

    @Override
    public void enableJvmMemoryInfoPublishing(boolean shouldPublish) {
        this.shouldPublishJvmMemoryInfo = shouldPublish;
    }

    @Override
    public WorkerProcess build() {
        final WorkerJvmMemoryStatus memoryStatus = shouldPublishJvmMemoryInfo ? new WorkerJvmMemoryStatus() : null;
        final DefaultWorkerProcess workerProcess = new DefaultWorkerProcess(connectTimeoutSeconds, TimeUnit.SECONDS, memoryStatus);
        ConnectionAcceptor acceptor = server.accept(new Action<ObjectConnection>() {
            @Override
            public void execute(final ObjectConnection connection) {
                workerProcess.onConnect(connection, new Runnable() {
                    @Override
                    public void run() {
                        DefaultWorkerLoggingProtocol defaultWorkerLoggingProtocol = new DefaultWorkerLoggingProtocol(outputEventListener);
                        connection.useParameterSerializers(WorkerLoggingSerializer.create());
                        connection.addIncoming(WorkerLoggingProtocol.class, defaultWorkerLoggingProtocol);
                        if (shouldPublishJvmMemoryInfo) {
                            connection.useParameterSerializers(WorkerJvmMemoryInfoSerializer.create());
                            connection.addIncoming(WorkerJvmMemoryInfoProtocol.class, memoryStatus);
                        }
                    }
                });
            }
        });
        workerProcess.startAccepting(acceptor);
        Address localAddress = acceptor.getAddress();

        // Build configuration for GradleWorkerMain
        long id = idGenerator.generateId();
        String displayName = getBaseName() + " " + id;

        LOGGER.debug("Creating {}", displayName);
        LOGGER.debug("Using application classpath {}", applicationClasspath);
        LOGGER.debug("Using application module path {}", applicationModulePath);
        LOGGER.debug("Using implementation classpath {}", implementationClassPath);
        LOGGER.debug("Using implementation module path {}", implementationModulePath);

        JavaExecHandleBuilder javaCommand = getJavaCommand();
        javaCommand.setDisplayName(displayName);

        workerImplementationFactory.prepareJavaCommand(id, displayName, this, implementationClassPath, implementationModulePath, localAddress, javaCommand, shouldPublishJvmMemoryInfo);

        javaCommand.args("'" + displayName + "'");
        if (javaCommand.getMaxHeapSize() == null) {
            javaCommand.setMaxHeapSize("512m");
        }
        ExecHandle execHandle = javaCommand.build();

        workerProcess.setExecHandle(execHandle);

        return new MemoryRequestingWorkerProcess(workerProcess, memoryManager, MemoryAmount.parseNotation(javaCommand.getMinHeapSize()));
    }

    private static class MemoryRequestingWorkerProcess implements WorkerProcess {
        private final WorkerProcess delegate;
        private final MemoryManager memoryResourceManager;
        private final long memoryAmount;

        private MemoryRequestingWorkerProcess(WorkerProcess delegate, MemoryManager memoryResourceManager, long memoryAmount) {
            this.delegate = delegate;
            this.memoryResourceManager = memoryResourceManager;
            this.memoryAmount = memoryAmount;
        }

        @Override
        public WorkerProcess start() {
            memoryResourceManager.requestFreeMemory(memoryAmount);
            return delegate.start();
        }

        @Override
        public ObjectConnection getConnection() {
            return delegate.getConnection();
        }

        @Override
        public ExecResult waitForStop() {
            return delegate.waitForStop();
        }

        @Override
        public JvmMemoryStatus getJvmMemoryStatus() {
            return delegate.getJvmMemoryStatus();
        }

        @Override
        public void stopNow() {
            delegate.stopNow();
        }
    }

    private static class WorkerJvmMemoryStatus implements JvmMemoryStatus, WorkerJvmMemoryInfoProtocol {
        private JvmMemoryStatus snapshot;

        public WorkerJvmMemoryStatus() {
            this.snapshot = new JvmMemoryStatus() {
                @Override
                public long getMaxMemory() {
                    throw new IllegalStateException("JVM memory status has not been reported yet.");
                }

                @Override
                public long getCommittedMemory() {
                    throw new IllegalStateException("JVM memory status has not been reported yet.");
                }
            };
        }

        @Override
        public void sendJvmMemoryStatus(JvmMemoryStatus jvmMemoryStatus) {
            this.snapshot = jvmMemoryStatus;
        }

        @Override
        public long getMaxMemory() {
            return snapshot.getMaxMemory();
        }

        @Override
        public long getCommittedMemory() {
            return snapshot.getCommittedMemory();
        }
    }
}
