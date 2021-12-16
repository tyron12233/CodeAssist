package com.tyron.builder.project.impl;

import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.api.Project;

import java.io.File;
import java.util.List;

public class ModuleImpl implements Module {

    public ModuleImpl(File root) {

    }

    @Override
    public ModuleType getModuleType() {
        return null;
    }

    @Override
    public List<Module> getDependingModules() {
        return null;
    }

    @Override
    public void addDependingModule(Module module) {

    }

    @Override
    public Project getProject() {
        return null;
    }
}
