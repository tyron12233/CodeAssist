package org.gradle.process.internal;

import org.gradle.api.NonExtensible;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;

@NonExtensible
public interface JavaExecAction extends JavaExecSpec {
    ExecResult execute();
}
