package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.ExecSpec;
import com.tyron.builder.process.JavaExecSpec;

public interface ProcessOperations {

    ExecResult javaexec(Action<? super JavaExecSpec> action);

    ExecResult exec(Action<? super ExecSpec> action);

}