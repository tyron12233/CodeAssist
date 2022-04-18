package com.tyron.builder.api.internal;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.initialization.IncludedBuildSpec;

import java.util.List;

public interface SettingsInternal extends Settings {

    String BUILD_SRC = "buildSrc";

    @Override
    StartParameter getStartParameter();

//    ScriptSource getSettingsScript();

    ProjectRegistry<DefaultProjectDescriptor> getProjectRegistry();

    void setDefaultProject(DefaultProjectDescriptor defaultProjectDescriptor);

    DefaultProjectDescriptor getDefaultProject();


    ClassLoaderScope getClassLoaderScope();

    List<IncludedBuildSpec> getIncludedBuilds();
}
