package com.tyron.builder;

import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.api.invocation.Gradle;

/**
 * <p>A {@code BuildListener} is notified of the major lifecycle events as a build is executed.</p>
 *
 * @see Gradle#addListener(Object)
 */
@EventScope(Scopes.Build.class)
public interface BuildListener {

    /**
     * Called when the build settings are about to be loaded and evaluated.
     *
     * @param settings The settings. Never null.
     * @since 6.0
     */
    default void beforeSettings(Settings settings) {}

    /**
     * <p>Called when the build settings have been loaded and evaluated. The settings object is fully configured and is
     * ready to use to load the build projects.</p>
     *
     * @param settings The settings. Never null.
     */
    void settingsEvaluated(Settings settings);

    /**
     * <p>Called when the projects for the build have been created from the settings. None of the projects have been
     * evaluated.</p>
     *
     * @param gradle The build which has been loaded. Never null.
     */
    void projectsLoaded(Gradle gradle);

    /**
     * <p>Called when all projects for the build have been evaluated. The project objects are fully configured and are
     * ready to use to populate the task graph.</p>
     *
     * @param gradle The build which has been evaluated. Never null.
     */
    void projectsEvaluated(Gradle gradle);

    /**
     * <p>Called when the build is completed. All selected tasks have been executed.</p>
     *
     * @param result The result of the build. Never null.
     * @deprecated This method is not supported when configuration caching is enabled.
     */
    @Deprecated
    void buildFinished(BuildResult result);
}
