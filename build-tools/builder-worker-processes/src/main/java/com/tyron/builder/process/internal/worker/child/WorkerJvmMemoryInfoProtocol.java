package com.tyron.builder.process.internal.worker.child;

import com.tyron.builder.process.internal.health.memory.JvmMemoryStatus;

public interface WorkerJvmMemoryInfoProtocol {
    void sendJvmMemoryStatus(JvmMemoryStatus jvmMemoryStatus);
}
