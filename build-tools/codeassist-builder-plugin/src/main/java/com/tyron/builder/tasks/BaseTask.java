package com.tyron.builder.tasks;

import com.tyron.builder.gradle.internal.tasks.VariantAwareTask;
import com.tyron.builder.plugin.builder.AndroidBuilder;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Internal;
import org.gradle.workers.WorkerExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.File;

import javax.inject.Inject;

public abstract class BaseTask extends DefaultTask implements VariantAwareTask {

    @NotNull
    @Override
    public String getVariantName() {
        return "debug";
    }

    @Override
    public void setVariantName(@NotNull String value) {

    }

    @Inject
    public abstract WorkerExecutor getWorkerExecutor();

    @Internal
    public AndroidBuilder getBuilder() {
        throw new UnsupportedOperationException();
    }

    protected static void emptyFolder(File folder) {
        deleteFolder(folder);
        folder.mkdirs();
    }

    protected static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null && files.length > 0) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteFolder(file);
                } else {
                    file.delete();
                }
            }
        }
        folder.delete();
    }
}
