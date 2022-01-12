package com.tyron.builder.compiler;

import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class ProjectBuilder {

    private final List<Module> mModules;
    private final Project mProject;
    private final ILogger mLogger;

    public ProjectBuilder(Project project, ILogger logger) throws IOException {
        mProject = project;
        mLogger = logger;
        mModules = project.getBuildOrder();
    }

    public void build(BuildType type) throws IOException, CompilationFailedException {
        for (Module module : mModules) {
            module.clear();
            module.open();
            module.index();

            String moduleType = module.getSettings().getString(ModuleSettings.MODULE_TYPE,
                    "android_app");
            switch (Objects.requireNonNull(moduleType)) {
                case "library":
                    JarBuilder jarBuilder = new JarBuilder((JavaModule) module, mLogger);
                    jarBuilder.build(type);
                    break;
                case "android_app":
                    AndroidAppBuilder androidAppBuilder =
                            new AndroidAppBuilder((AndroidModule) module, mLogger);
                    androidAppBuilder.build(type);
                    break;
            }
        }
    }

}
