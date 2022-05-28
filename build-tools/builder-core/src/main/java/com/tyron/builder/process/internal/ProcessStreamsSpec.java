package com.tyron.builder.process.internal;

import java.io.InputStream;
import java.io.OutputStream;


public class ProcessStreamsSpec {

    private InputStream standardInput;
    private OutputStream standardOutput;
    private OutputStream errorOutput;

    public ProcessStreamsSpec setStandardInput(InputStream inputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream == null!");
        }
        standardInput = inputStream;
        return this;
    }

    public InputStream getStandardInput() {
        return standardInput;
    }

    public ProcessStreamsSpec setStandardOutput(OutputStream outputStream) {
        if (outputStream == null) {
            throw new IllegalArgumentException("outputStream == null!");
        }
        standardOutput = outputStream;
        return this;
    }

    public OutputStream getStandardOutput() {
        return standardOutput;
    }

    public ProcessStreamsSpec setErrorOutput(OutputStream outputStream) {
        if (outputStream == null) {
            throw new IllegalArgumentException("outputStream == null!");
        }
        errorOutput = outputStream;
        return this;
    }

    public OutputStream getErrorOutput() {
        return errorOutput;
    }
}
