package com.tyron.builder.project.api;

import java.util.List;

public interface Module {

    enum ModuleType {
        LIBRARY,
        ANDROID
    }

    ModuleType getModuleType();

    List<Module> getDependingModules();

    /**
     * Add a module that this module depends on
     */
    void addDependingModule(Module module);

    Project getProject();
}
