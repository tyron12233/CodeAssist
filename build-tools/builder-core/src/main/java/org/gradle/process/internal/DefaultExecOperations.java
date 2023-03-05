package org.gradle.process.internal;

import org.gradle.api.Action;
import org.gradle.api.internal.ProcessOperations;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;
import org.gradle.process.JavaExecSpec;


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
