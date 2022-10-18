package org.gradle.plugins.ide.internal.resolver;

import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.io.File;
import java.util.Collection;

public class UnresolvedIdeDependencyHandler {

    private final Logger logger = Logging.getLogger(UnresolvedIdeDependencyHandler.class);

    public void log(Collection<UnresolvedDependencyResult> deps) {
        for (UnresolvedDependencyResult dep : deps) {
            log(dep);
        }
    }

    public void log(UnresolvedDependencyResult dep) {
        logger.warn("Could not resolve: " + dep.getAttempted());
        logger.debug("Could not resolve: " + dep.getAttempted(), dep.getFailure());
    }

    public File asFile(UnresolvedDependencyResult dep, File parent) {
        return new File(parent, "unresolved dependency - " + dep.getAttempted().getDisplayName().replaceAll(":", " "));
    }
}
