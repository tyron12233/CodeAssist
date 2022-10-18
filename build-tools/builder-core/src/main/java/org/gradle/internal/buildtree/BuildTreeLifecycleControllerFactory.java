package org.gradle.internal.buildtree;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.build.BuildLifecycleController;

@ServiceScope(Scopes.BuildTree.class)
public interface BuildTreeLifecycleControllerFactory {
    BuildTreeLifecycleController createRootBuildController(BuildLifecycleController targetBuild, BuildTreeWorkExecutor workExecutor, BuildTreeFinishExecutor finishExecutor);

    BuildTreeLifecycleController createController(BuildLifecycleController targetBuild, BuildTreeWorkExecutor workExecutor, BuildTreeFinishExecutor finishExecutor);
}