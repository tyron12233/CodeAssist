package com.tyron.builder.process.internal;

import com.tyron.builder.api.NonExtensible;
import com.tyron.builder.process.ExecResult;
import com.tyron.builder.process.ExecSpec;

@NonExtensible
public interface ExecAction extends ExecSpec {
    ExecResult execute() throws ExecException;
}
