package org.gradle.tooling.internal.provider.runner;

import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.buildtree.BuildTreeModelController;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.internal.work.WorkerThreadRegistry;

@ServiceScope(Scopes.BuildTree.class)
public class BuildControllerFactory {
    private final WorkerThreadRegistry workerThreadRegistry;
    private final BuildCancellationToken buildCancellationToken;
    private final BuildStateRegistry buildStateRegistry;

    public BuildControllerFactory(WorkerThreadRegistry workerThreadRegistry, BuildCancellationToken buildCancellationToken, BuildStateRegistry buildStateRegistry) {
        this.workerThreadRegistry = workerThreadRegistry;
        this.buildCancellationToken = buildCancellationToken;
        this.buildStateRegistry = buildStateRegistry;
    }

    public DefaultBuildController controllerFor(BuildTreeModelController controller) {
        return new DefaultBuildController(controller, workerThreadRegistry, buildCancellationToken, buildStateRegistry);
    }
}