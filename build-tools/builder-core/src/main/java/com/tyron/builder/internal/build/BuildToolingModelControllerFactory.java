package com.tyron.builder.internal.build;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.BuildTree.class)
public interface BuildToolingModelControllerFactory {
    BuildToolingModelController createController(BuildState owner, BuildLifecycleController controller);
}