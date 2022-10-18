package org.gradle.internal.nativeintegration.processenvironment;

import net.rubygrapefruit.platform.Process;

import java.io.File;

public class NativePlatformBackedProcessEnvironment extends AbstractProcessEnvironment {
    private final Process process;

    public NativePlatformBackedProcessEnvironment(Process process) {
        this.process = process;
    }

    @Override
    protected void removeNativeEnvironmentVariable(String name) {
        process.setEnvironmentVariable(name, null);
    }

    @Override
    protected void setNativeEnvironmentVariable(String name, String value) {
        process.setEnvironmentVariable(name, value);
    }

    @Override
    protected void setNativeProcessDir(File processDir) {
        process.setWorkingDirectory(processDir);
    }

    @Override
    public File getProcessDir() {
        return process.getWorkingDirectory();
    }

    @Override
    public Long getPid() {
        return (long) process.getProcessId();
    }

    @Override
    public void detachProcess() {
        process.detach();
    }
}
