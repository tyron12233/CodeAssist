package com.tyron.builder.api.internal;

import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.api.internal.project.ProjectRegistry;

public interface SettingsInternal extends Settings {

    String BUILD_SRC = "buildSrc";

    @Override
    StartParameter getStartParameter();

//    ScriptSource getSettingsScript();

    ProjectRegistry<DefaultProjectDescriptor> getProjectRegistry();

    DefaultProjectDescriptor getDefaultProject();


}
