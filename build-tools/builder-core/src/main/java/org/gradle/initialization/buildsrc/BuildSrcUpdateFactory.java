package org.gradle.initialization.buildsrc;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.classpath.CachedClasspathTransformer;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.classpath.DefaultClassPath;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.Collection;

import static org.gradle.internal.classpath.CachedClasspathTransformer.StandardTransform.BuildLogic;

public class BuildSrcUpdateFactory {
    private static final Logger LOGGER = Logging.getLogger(BuildSrcUpdateFactory.class);

    private final BuildTreeLifecycleController buildController;
    private final BuildSrcBuildListenerFactory listenerFactory;
    private final CachedClasspathTransformer cachedClasspathTransformer;

    public BuildSrcUpdateFactory(BuildTreeLifecycleController buildController, BuildSrcBuildListenerFactory listenerFactory, CachedClasspathTransformer cachedClasspathTransformer) {
        this.buildController = buildController;
        this.listenerFactory = listenerFactory;
        this.cachedClasspathTransformer = cachedClasspathTransformer;
    }

    @Nonnull
    public ClassPath create() {
        Collection<File> classpath = build();
        LOGGER.debug("Gradle source classpath is: {}", classpath);
        return cachedClasspathTransformer.transform(DefaultClassPath.of(classpath), BuildLogic);
    }

    private Collection<File> build() {
        BuildSrcBuildListenerFactory.Listener listener = listenerFactory.create();
        buildController.beforeBuild(gradle -> gradle.addListener(listener));

        buildController.scheduleAndRunTasks();

        return listener.getRuntimeClasspath();
    }
}