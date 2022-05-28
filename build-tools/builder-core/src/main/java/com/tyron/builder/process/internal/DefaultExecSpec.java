package com.tyron.builder.process.internal;

import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.process.BaseExecSpec;
import com.tyron.builder.process.CommandLineArgumentProvider;
import com.tyron.builder.process.ExecSpec;

import javax.inject.Inject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;


public class DefaultExecSpec extends DefaultProcessForkOptions implements ExecSpec, ProcessArgumentsSpec.HasExecutable {

    private boolean ignoreExitValue;
    private final ProcessStreamsSpec streamsSpec = new ProcessStreamsSpec();
    private final ProcessArgumentsSpec argumentsSpec = new ProcessArgumentsSpec(this);

    @Inject
    public DefaultExecSpec(PathToFileResolver resolver) {
        super(resolver);
    }

    public void copyTo(ExecSpec targetSpec) {
        // Fork options
        super.copyTo(targetSpec);
        // BaseExecSpec
        copyBaseExecSpecTo(this, targetSpec);
        // ExecSpec
        targetSpec.setArgs(getArgs());
        targetSpec.getArgumentProviders().addAll(getArgumentProviders());
    }

    static void copyBaseExecSpecTo(BaseExecSpec source, BaseExecSpec target) {
        target.setIgnoreExitValue(source.isIgnoreExitValue());
        if (source.getStandardInput() != null) {
            target.setStandardInput(source.getStandardInput());
        }
        if (source.getStandardOutput() != null) {
            target.setStandardOutput(source.getStandardOutput());
        }
        if (source.getErrorOutput() != null) {
            target.setErrorOutput(source.getErrorOutput());
        }
    }

    @Override
    public List<String> getCommandLine() {
        return argumentsSpec.getCommandLine();
    }

    @Override
    public ExecSpec commandLine(Object... arguments) {
        argumentsSpec.commandLine(arguments);
        return this;
    }

    @Override
    public ExecSpec commandLine(Iterable<?> args) {
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
    public ExecSpec args(Object... args) {
        argumentsSpec.args(args);
        return this;
    }

    @Override
    public ExecSpec args(Iterable<?> args) {
        argumentsSpec.args(args);
        return this;
    }

    @Override
    public ExecSpec setArgs(List<String> arguments) {
        argumentsSpec.setArgs(arguments);
        return this;
    }

    @Override
    public ExecSpec setArgs(Iterable<?> arguments) {
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
    public ExecSpec setIgnoreExitValue(boolean ignoreExitValue) {
        this.ignoreExitValue = ignoreExitValue;
        return this;
    }

    @Override
    public boolean isIgnoreExitValue() {
        return ignoreExitValue;
    }

    @Override
    public BaseExecSpec setStandardInput(InputStream inputStream) {
        streamsSpec.setStandardInput(inputStream);
        return this;
    }

    @Override
    public InputStream getStandardInput() {
        return streamsSpec.getStandardInput();
    }

    @Override
    public BaseExecSpec setStandardOutput(OutputStream outputStream) {
        streamsSpec.setStandardOutput(outputStream);
        return this;
    }

    @Override
    public OutputStream getStandardOutput() {
        return streamsSpec.getStandardOutput();
    }

    @Override
    public BaseExecSpec setErrorOutput(OutputStream outputStream) {
        streamsSpec.setErrorOutput(outputStream);
        return this;
    }

    @Override
    public OutputStream getErrorOutput() {
        return streamsSpec.getErrorOutput();
    }
}
