package org.gradle.configuration;


import org.gradle.api.internal.GradleInternal;

public interface InitScriptProcessor {
    void process(Object initScript, GradleInternal gradle);
}
