package com.tyron.builder.project.api;

import java.io.IOException;

public interface ModuleManager<T extends Module> {

    void initialize() throws IOException;

    void addDependingModule(Module module);

    T getModule();
}
