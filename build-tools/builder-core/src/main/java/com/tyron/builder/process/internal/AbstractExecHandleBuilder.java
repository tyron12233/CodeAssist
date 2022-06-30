package com.tyron.builder.process.internal;

import org.apache.commons.lang3.StringUtils;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.file.PathToFileResolver;
import com.tyron.builder.process.BaseExecSpec;
import com.tyron.builder.process.internal.streams.EmptyStdInStreamsHandler;
import com.tyron.builder.process.internal.streams.ForwardStdinStreamsHandler;
import com.tyron.builder.process.internal.streams.OutputStreamsForwarder;
import com.tyron.builder.process.internal.streams.SafeStreams;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public abstract class AbstractExecHandleBuilder extends DefaultProcessForkOptions implements BaseExecSpec {
    private static final EmptyStdInStreamsHandler DEFAULT_STDIN = new EmptyStdInStreamsHandler();
    private final BuildCancellationToken buildCancellationToken;
    private final List<ExecHandleListener> listeners = new ArrayList<>();
    private final ProcessStreamsSpec streamsSpec = new ProcessStreamsSpec();
    private StreamsHandler inputHandler = DEFAULT_STDIN;
    private String displayName;
    private boolean ignoreExitValue;
    private boolean redirectErrorStream;
    private StreamsHandler streamsHandler;
    private int timeoutMillis = Integer.MAX_VALUE;
    protected boolean daemon;
    private final Executor executor;

    AbstractExecHandleBuilder(PathToFileResolver fileResolver, Executor executor, BuildCancellationToken buildCancellationToken) {
        super(fileResolver);
        this.buildCancellationToken = buildCancellationToken;
        this.executor = executor;
        streamsSpec.setStandardOutput(SafeStreams.systemOut());
        streamsSpec.setErrorOutput(SafeStreams.systemErr());
        streamsSpec.setStandardInput(SafeStreams.emptyInput());
    }

    public abstract List<String> getAllArguments();

    protected List<String> getEffectiveArguments() {
        return getAllArguments();
    }

    @Override
    public List<String> getCommandLine() {
        List<String> commandLine = new ArrayList<>();
        commandLine.add(getExecutable());
        commandLine.addAll(getAllArguments());
        return commandLine;
    }

    @Override
    public AbstractExecHandleBuilder setStandardInput(InputStream inputStream) {
        streamsSpec.setStandardInput(inputStream);
        this.inputHandler = new ForwardStdinStreamsHandler(inputStream);
        return this;
    }

    public StreamsHandler getInputHandler() {
        return inputHandler;
    }

    @Override
    public InputStream getStandardInput() {
        return streamsSpec.getStandardInput();
    }

    @Override
    public AbstractExecHandleBuilder setStandardOutput(OutputStream outputStream) {
        streamsSpec.setStandardOutput(outputStream);
        return this;
    }

    @Override
    public OutputStream getStandardOutput() {
        return streamsSpec.getStandardOutput();
    }

    @Override
    public AbstractExecHandleBuilder setErrorOutput(OutputStream outputStream) {
        streamsSpec.setErrorOutput(outputStream);
        return this;
    }

    @Override
    public OutputStream getErrorOutput() {
        return streamsSpec.getErrorOutput();
    }

    @Override
    public boolean isIgnoreExitValue() {
        return ignoreExitValue;
    }

    @Override
    public AbstractExecHandleBuilder setIgnoreExitValue(boolean ignoreExitValue) {
        this.ignoreExitValue = ignoreExitValue;
        return this;
    }

    public String getDisplayName() {
        return displayName == null ? String.format("command '%s'", getExecutable()) : displayName;
    }

    public AbstractExecHandleBuilder setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    public AbstractExecHandleBuilder listener(ExecHandleListener listener) {
        this.listeners.add(listener);
        return this;
    }

    public ExecHandle build() {
        String executable = getExecutable();
        if (StringUtils.isEmpty(executable)) {
            throw new IllegalStateException("execCommand == null!");
        }

        StreamsHandler effectiveOutputHandler = getEffectiveStreamsHandler();
        return new DefaultExecHandle(getDisplayName(), getWorkingDir(), executable, getEffectiveArguments(), getActualEnvironment(),
            effectiveOutputHandler, inputHandler, listeners, redirectErrorStream, timeoutMillis, daemon, executor, buildCancellationToken);
    }

    private StreamsHandler getEffectiveStreamsHandler() {
        StreamsHandler effectiveHandler;
        if (this.streamsHandler != null) {
            effectiveHandler = this.streamsHandler;
        } else {
            boolean shouldReadErrorStream = !redirectErrorStream;
            effectiveHandler = new OutputStreamsForwarder(streamsSpec.getStandardOutput(), streamsSpec.getErrorOutput(), shouldReadErrorStream);
        }
        return effectiveHandler;
    }

    public AbstractExecHandleBuilder streamsHandler(StreamsHandler streamsHandler) {
        this.streamsHandler = streamsHandler;
        return this;
    }

    /**
     * Merge the process' error stream into its output stream
     */
    public AbstractExecHandleBuilder redirectErrorStream() {
        this.redirectErrorStream = true;
        return this;
    }

    public AbstractExecHandleBuilder setTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        return this;
    }
}
