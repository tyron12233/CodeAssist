package com.tyron.builder.process.internal;

import com.tyron.builder.api.NonExtensible;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.JavaExecSpec;

@NonExtensible
public interface JavaExecAction extends JavaExecSpec {
    ExecResult execute();
}
