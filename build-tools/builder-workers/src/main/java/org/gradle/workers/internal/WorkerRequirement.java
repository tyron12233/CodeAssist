package org.gradle.workers.internal;

import java.io.File;

public interface WorkerRequirement {
    File getWorkerDirectory();
}
