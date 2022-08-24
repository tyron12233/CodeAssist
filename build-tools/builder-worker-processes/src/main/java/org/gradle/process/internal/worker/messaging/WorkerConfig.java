package org.gradle.process.internal.worker.messaging;

import org.gradle.api.Action;
import org.gradle.api.logging.LogLevel;
import org.gradle.internal.remote.internal.inet.MultiChoiceAddress;
import org.gradle.process.internal.worker.WorkerProcessContext;

import java.io.Serializable;

/**
 * All configuration options to be transferred to a worker process during worker startup.
 */
public class WorkerConfig {
    private final LogLevel logLevel;
    private final boolean publishJvmMemoryInfo;
    private final String gradleUserHomeDirPath;
    private final MultiChoiceAddress serverAddress;
    private final long workerId;
    private final String displayName;
    private final Action<? super WorkerProcessContext> workerAction;

    public WorkerConfig(LogLevel logLevel, boolean publishJvmMemoryInfo, String gradleUserHomeDirPath, MultiChoiceAddress serverAddress, long workerId, String displayName, Action<? super WorkerProcessContext> workerAction) {
        this.logLevel = logLevel;
        this.publishJvmMemoryInfo = publishJvmMemoryInfo;
        this.gradleUserHomeDirPath = gradleUserHomeDirPath;
        this.serverAddress = serverAddress;
        this.workerId = workerId;
        this.displayName = displayName;
        this.workerAction = workerAction;

        assert workerAction instanceof Serializable;
    }

    public LogLevel getLogLevel() {
        return logLevel;
    }

    /**
     * @return True if process info should be published. False otherwise.
     */
    public boolean shouldPublishJvmMemoryInfo() {
        return publishJvmMemoryInfo;
    }

    /**
     * @return The absolute path to the Gradle user home directory.
     */
    public String getGradleUserHomeDirPath() {
        return gradleUserHomeDirPath;
    }

    public MultiChoiceAddress getServerAddress() {
        return serverAddress;
    }

    public long getWorkerId() {
        return workerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Action<? super WorkerProcessContext> getWorkerAction() {
        return workerAction;
    }
}