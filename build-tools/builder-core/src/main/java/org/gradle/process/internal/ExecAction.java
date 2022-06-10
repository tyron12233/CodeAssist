package org.gradle.process.internal;

import org.gradle.api.NonExtensible;
import org.gradle.process.ExecResult;
import org.gradle.process.ExecSpec;

@NonExtensible
public interface ExecAction extends ExecSpec {
    ExecResult execute() throws ExecException;
}
