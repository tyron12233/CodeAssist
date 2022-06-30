package com.tyron.builder.composite.internal;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.IncludedBuildFactory;
import com.tyron.builder.internal.build.IncludedBuildState;
import com.tyron.builder.internal.buildtree.BuildTreeState;

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
