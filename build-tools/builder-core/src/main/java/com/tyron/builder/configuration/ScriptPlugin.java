package com.tyron.builder.configuration;

import com.tyron.builder.api.Plugin;
import com.tyron.builder.groovy.scripts.ScriptSource;

public interface ScriptPlugin extends Plugin<Object> {

    ScriptSource getSource();

    @Override
    void apply(Object target);

}
