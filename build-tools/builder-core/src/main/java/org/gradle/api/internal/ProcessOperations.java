package org.gradle.api.internal;

import org.gradle.api.Action;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;
import org.gradle.process.JavaExecSpec;

public interface ProcessOperations {

    ExecResult javaexec(Action<? super JavaExecSpec> action);

    ExecResult exec(Action<? super ExecSpec> action);

}