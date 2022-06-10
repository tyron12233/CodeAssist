package org.gradle.configuration;

import org.gradle.api.Plugin;
import org.gradle.groovy.scripts.ScriptSource;

public interface ScriptPlugin extends Plugin<Object> {

    ScriptSource getSource();

    @Override
    void apply(Object target);

}
