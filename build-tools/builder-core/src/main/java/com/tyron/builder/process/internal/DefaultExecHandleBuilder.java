package com.tyron.builder.process.internal;

import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.process.CommandLineArgumentProvider;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Use {@link ExecHandleFactory} instead.
 */
public class DefaultExecHandleBuilder extends AbstractExecHandleBuilder implements ExecHandleBuilder, ProcessArgumentsSpec.HasExecutable {

    private final ProcessArgumentsSpec argumentsSpec = new ProcessArgumentsSpec(this);

    public DefaultExecHandleBuilder(PathToFileResolver fileResolver, Executor executor) {
        this(fileResolver, executor, new DefaultBuildCancellationToken());
    }

    public DefaultExecHandleBuilder(PathToFileResolver fileResolver, Executor executor, BuildCancellationToken buildCancellationToken) {
        super(fileResolver, executor, buildCancellationToken);
    }

    @Override
    public DefaultExecHandleBuilder executable(Object executable) {
        super.executable(executable);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder commandLine(Object... arguments) {
        argumentsSpec.commandLine(arguments);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder commandLine(Iterable<?> args) {
        argumentsSpec.commandLine(args);
        return this;
    }

    @Override
    public void setCommandLine(List<String> args) {
        argumentsSpec.commandLine(args);
    }

    @Override
    public void setCommandLine(Object... args) {
        argumentsSpec.commandLine(args);
    }

    @Override
    public void setCommandLine(Iterable<?> args) {
        argumentsSpec.commandLine(args);
    }

    @Override
    public DefaultExecHandleBuilder args(Object... args) {
        argumentsSpec.args(args);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder args(Iterable<?> args) {
        argumentsSpec.args(args);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder setArgs(List<String> arguments) {
        argumentsSpec.setArgs(arguments);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder setArgs(Iterable<?> arguments) {
        argumentsSpec.setArgs(arguments);
        return this;
    }

    @Override
    public List<String> getArgs() {
        return argumentsSpec.getArgs();
    }

    @Override
    public List<CommandLineArgumentProvider> getArgumentProviders() {
        return argumentsSpec.getArgumentProviders();
    }

    @Override
    public List<String> getAllArguments() {
        return argumentsSpec.getAllArguments();
    }

    @Override
    public DefaultExecHandleBuilder setIgnoreExitValue(boolean ignoreExitValue) {
        super.setIgnoreExitValue(ignoreExitValue);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder workingDir(Object dir) {
        super.workingDir(dir);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder setDisplayName(String displayName) {
        super.setDisplayName(displayName);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder redirectErrorStream() {
        super.redirectErrorStream();
        return this;
    }

    @Override
    public DefaultExecHandleBuilder setStandardOutput(OutputStream outputStream) {
        super.setStandardOutput(outputStream);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder setStandardInput(InputStream inputStream) {
        super.setStandardInput(inputStream);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder streamsHandler(StreamsHandler streamsHandler) {
        super.streamsHandler(streamsHandler);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder listener(ExecHandleListener listener) {
        super.listener(listener);
        return this;
    }

    @Override
    public DefaultExecHandleBuilder setTimeout(int timeoutMillis) {
        super.setTimeout(timeoutMillis);
        return this;
    }

    @Override
    public ExecHandleBuilder setDaemon(boolean daemon) {
        super.daemon = daemon;
        return this;
    }
}
