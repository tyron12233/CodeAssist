package org.gradle.process.internal.worker.child;

import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.util.internal.GFileUtils;

import java.io.File;

public class DefaultWorkerDirectoryProvider implements WorkerDirectoryProvider {
    private final File gradleUserHomeDir;

    public DefaultWorkerDirectoryProvider(GradleUserHomeDirProvider gradleUserHomeDirProvider) {
        this.gradleUserHomeDir = gradleUserHomeDirProvider.getGradleUserHomeDirectory();
    }

    @Override
    public File getWorkingDirectory() {
        File defaultWorkerDirectory = new File(gradleUserHomeDir, "workers");
        if (!defaultWorkerDirectory.exists()) {
            GFileUtils.mkdirs(defaultWorkerDirectory);
        }
        return defaultWorkerDirectory;
    }
}
