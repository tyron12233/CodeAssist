package org.gradle.api.internal;

import org.gradle.StartParameter;
import org.gradle.api.initialization.Settings;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.plugins.PluginAwareInternal;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.initialization.DefaultProjectDescriptor;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.initialization.IncludedBuildSpec;

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
