package com.tyron.builder.internal.buildTree;

import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.service.scopes.ServiceScope;
import com.tyron.builder.internal.build.BuildLifecycleController;

@ServiceScope(Scopes.BuildTree.class)
public interface BuildTreeLifecycleControllerFactory {
    BuildTreeLifecycleController createRootBuildController(BuildLifecycleController targetBuild, BuildTreeWorkExecutor workExecutor, BuildTreeFinishExecutor finishExecutor);

    BuildTreeLifecycleController createController(BuildLifecycleController targetBuild, BuildTreeWorkExecutor workExecutor, BuildTreeFinishExecutor finishExecutor);
}