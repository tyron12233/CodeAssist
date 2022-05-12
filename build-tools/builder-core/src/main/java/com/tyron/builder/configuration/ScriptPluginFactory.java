package com.tyron.builder.configuration;

import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.groovy.scripts.ScriptSource;

public interface ScriptPluginFactory {
    ScriptPlugin create(ScriptSource scriptSource, ScriptHandler scriptHandler, ClassLoaderScope targetScope, ClassLoaderScope baseScope, boolean topLevelScript);
}
