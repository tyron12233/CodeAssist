package org.gradle.internal.build;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.BuildTree.class)
public interface BuildToolingModelControllerFactory {
    BuildToolingModelController createController(BuildState owner, BuildLifecycleController controller);
}