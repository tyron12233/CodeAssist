package org.gradle.composite.internal;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.internal.reflect.DirectInstantiator;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.util.Path;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.IncludedBuildFactory;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.internal.buildtree.BuildTreeState;

import java.io.File;

import javax.inject.Inject;

public class DefaultIncludedBuildFactory implements IncludedBuildFactory {
    private final BuildTreeState buildTree;
    private final Instantiator instantiator;

    @Inject
    public DefaultIncludedBuildFactory(BuildTreeState buildTree) {
        this(buildTree, DirectInstantiator.INSTANCE);
    }

    public DefaultIncludedBuildFactory(
            BuildTreeState buildTree,
            Instantiator instantiator
    ) {
        this.buildTree = buildTree;
        this.instantiator = instantiator;
    }

    private void validateBuildDirectory(File dir) {
        if (!dir.exists()) {
            throw new InvalidUserDataException(String.format("Included build '%s' does not exist.", dir));
        }
        if (!dir.isDirectory()) {
            throw new InvalidUserDataException(String.format("Included build '%s' is not a directory.", dir));
        }
    }

    @Override
    public IncludedBuildState createBuild(BuildIdentifier buildIdentifier, Path identityPath, BuildDefinition buildDefinition, boolean isImplicit, BuildState owner) {
        validateBuildDirectory(buildDefinition.getBuildRootDir());
        return new DefaultIncludedBuild(
                buildIdentifier,
                identityPath,
                buildDefinition,
                isImplicit,
                owner,
                buildTree,
                instantiator
        );
    }
}
