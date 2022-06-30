package com.tyron.builder.process.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.ProcessOperations;
import com.tyron.builder.process.ExecOperations;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.ExecSpec;
import com.tyron.builder.process.JavaExecSpec;


public class DefaultExecOperations implements ExecOperations {

    private final ProcessOperations processOperations;

    public DefaultExecOperations(ProcessOperations processOperations) {
        this.processOperations = processOperations;
    }

    @Override
    public ExecResult exec(Action<? super ExecSpec> action) {
        return processOperations.exec(action);
    }

    @Override
    public ExecResult javaexec(Action<? super JavaExecSpec> action) {
        return processOperations.javaexec(action);
    }
}
