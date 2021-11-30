package com.tyron.builder.project.api;

import com.tyron.builder.compiler2.api.Task;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.util.List;

public interface Module {

    void addDependingModule(Module module);

    List<Module> getDependingModules();

    <T> T getData(Key<T> key);

    <T> void putData(Key<T> key, T value);

    /**
     * Tasks needed to run to compile this module
     */
    List<Task> getTasks();
}
