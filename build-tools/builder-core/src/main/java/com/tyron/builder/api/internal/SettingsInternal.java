package com.tyron.builder.api.internal;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.plugins.PluginAwareInternal;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.initialization.IncludedBuildSpec;

import java.util.List;

public interface SettingsInternal extends Settings, PluginAwareInternal {

    String BUILD_SRC = "buildSrc";

    @Override
    StartParameter getStartParameter();

//    ScriptSourceIn getSettingsScript();

    @Override
    GradleInternal getGradle();

    ProjectRegistry<DefaultProjectDescriptor> getProjectRegistry();

    void setDefaultProject(DefaultProjectDescriptor defaultProjectDescriptor);

    DefaultProjectDescriptor getDefaultProject();


    ClassLoaderScope getBaseClassLoaderScope();

    ClassLoaderScope getClassLoaderScope();

    List<IncludedBuildSpec> getIncludedBuilds();

    ScriptSource getSettingsScript();
}
