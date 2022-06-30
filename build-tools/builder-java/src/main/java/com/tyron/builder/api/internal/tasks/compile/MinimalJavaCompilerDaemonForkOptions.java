package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.tasks.compile.ForkOptions;

import javax.annotation.Nullable;
import java.io.File;

public class MinimalJavaCompilerDaemonForkOptions extends MinimalCompilerDaemonForkOptions {
    private String executable;

    private String tempDir;

    private File javaHome;

    public MinimalJavaCompilerDaemonForkOptions(ForkOptions forkOptions) {
        super(forkOptions);
        this.executable = forkOptions.getExecutable();
        this.tempDir = forkOptions.getTempDir();
        this.javaHome = forkOptions.getJavaHome();
        setJvmArgs(forkOptions.getAllJvmArgs());
    }

    @Nullable
    public String getExecutable() {
        return executable;
    }

    public void setExecutable(@Nullable String executable) {
        this.executable = executable;
    }

    @Nullable
    public String getTempDir() {
        return tempDir;
    }

    public void setTempDir(@Nullable String tempDir) {
        this.tempDir = tempDir;
    }

    @Nullable
    public File getJavaHome() {
        return javaHome;
    }

    public void setJavaHome(@Nullable File javaHome) {
        this.javaHome = javaHome;
    }
}
