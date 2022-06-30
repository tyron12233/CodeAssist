package com.tyron.builder.internal.composite;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.initialization.IncludedBuildSpec;
import com.tyron.builder.initialization.SettingsLoader;
import com.tyron.builder.internal.build.BuildIncluder;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildState;
import com.tyron.builder.internal.build.RootBuildState;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ChildBuildRegisteringSettingsLoader implements SettingsLoader {

    private final SettingsLoader delegate;
    private final BuildStateRegistry buildRegistry;

    private final BuildIncluder buildIncluder;

    public ChildBuildRegisteringSettingsLoader(SettingsLoader delegate, BuildStateRegistry buildRegistry, BuildIncluder buildIncluder) {
        this.delegate = delegate;
        this.buildRegistry = buildRegistry;
        this.buildIncluder = buildIncluder;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        SettingsInternal settings = delegate.findAndLoadSettings(gradle);

        // Add included builds defined in settings
        List<IncludedBuildSpec> includedBuilds = settings.getIncludedBuilds();
        if (!includedBuilds.isEmpty()) {
            Set<IncludedBuildInternal> children = new LinkedHashSet<>(includedBuilds.size());
            RootBuildState rootBuild = buildRegistry.getRootBuild();
            for (IncludedBuildSpec includedBuildSpec : includedBuilds) {
                if (!includedBuildSpec.rootDir.equals(rootBuild.getBuildRootDir())) {
                    IncludedBuildState includedBuild = buildIncluder.includeBuild(includedBuildSpec, gradle);
                    children.add(includedBuild.getModel());
                } else {
                    buildRegistry.registerSubstitutionsForRootBuild();
                    children.add(buildRegistry.getRootBuild().getModel());
                }
            }

            // Set the visible included builds
            gradle.setIncludedBuilds(children);
        } else {
            gradle.setIncludedBuilds(Collections.emptyList());
        }

        return settings;
    }

}
