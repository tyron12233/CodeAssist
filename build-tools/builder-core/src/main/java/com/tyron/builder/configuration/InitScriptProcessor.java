package com.tyron.builder.configuration;


import com.tyron.builder.api.internal.GradleInternal;

public interface InitScriptProcessor {
    void process(Object initScript, GradleInternal gradle);
}
