package org.gradle.process.internal.worker.child;

import org.gradle.process.internal.health.memory.JvmMemoryStatus;

public interface WorkerJvmMemoryInfoProtocol {
    void sendJvmMemoryStatus(JvmMemoryStatus jvmMemoryStatus);
}
