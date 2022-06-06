package com.tyron.builder.process.internal;

import com.google.common.collect.Maps;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.internal.jvm.Jvm;
import com.tyron.builder.process.ProcessForkOptions;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class DefaultProcessForkOptions implements ProcessForkOptions {
    private final PathToFileResolver resolver;
    private Object executable;
    private File workingDir;
    private Map<String, Object> environment;

    public DefaultProcessForkOptions(PathToFileResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public String getExecutable() {
        return executable == null ? null : executable.toString();
    }

    @Override
    public void setExecutable(String executable) {
        this.executable = executable;
    }

    @Override
    public void setExecutable(Object executable) {
        this.executable = executable;
    }

    @Override
    public ProcessForkOptions executable(Object executable) {
        setExecutable(executable);
        return this;
    }

    @Override
    public File getWorkingDir() {
        if (workingDir == null) {
            workingDir = resolver.resolve(".");
        }
        return workingDir;
    }

    @Override
    public void setWorkingDir(File dir) {
        this.workingDir = resolver.resolve(dir);
    }

    @Override
    public void setWorkingDir(Object dir) {
        this.workingDir = resolver.resolve(dir);
    }

    @Override
    public ProcessForkOptions workingDir(Object dir) {
        setWorkingDir(dir);
        return this;
    }

    @Override
    public Map<String, Object> getEnvironment() {
        if (environment == null) {
            setEnvironment(Jvm.current().getInheritableEnvironmentVariables(System.getenv()));
        }
        return environment;
    }

    public Map<String, String> getActualEnvironment() {
        return getActualEnvironment(this);
    }

    public static Map<String, String> getActualEnvironment(ProcessForkOptions forkOptions) {
        Map<String, String> actual = new HashMap<>();
        for (Map.Entry<String, Object> entry : forkOptions.getEnvironment().entrySet()) {
            actual.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return actual;
    }

    @Override
    public void setEnvironment(Map<String, ?> environmentVariables) {
        environment = Maps.newHashMap(environmentVariables);
    }

    @Override
    public ProcessForkOptions environment(String name, Object value) {
        getEnvironment().put(name, value);
        return this;
    }

    @Override
    public ProcessForkOptions environment(Map<String, ?> environmentVariables) {
        getEnvironment().putAll(environmentVariables);
        return this;
    }

    @Override
    public ProcessForkOptions copyTo(ProcessForkOptions target) {
        target.setExecutable(executable);
        target.setWorkingDir(getWorkingDir());
        target.setEnvironment(getEnvironment());
        return this;
    }
}
