package org.gradle.internal.composite;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.initialization.IncludedBuildSpec;
import org.gradle.initialization.SettingsLoader;
import org.gradle.internal.build.BuildIncluder;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.build.RootBuildState;

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
