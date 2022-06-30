package com.tyron.builder.initialization.layout;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.cache.scopes.BuildScopedCache;

import java.io.File;

/**
 * Contains information about the build layout, resolved after running the settings script and selecting the default project.
 */
@ServiceScope(Scopes.Build.class)
public class ResolvedBuildLayout {
    private final GradleInternal gradle;
    private final BuildLayout buildLayout;
    private final BuildScopedCache buildScopedCache;

    public ResolvedBuildLayout(GradleInternal gradle, BuildLayout buildLayout, BuildScopedCache buildScopedCache) {
        this.gradle = gradle;
        this.buildLayout = buildLayout;
        this.buildScopedCache = buildScopedCache;
    }

    /**
     * Returns the directory that Gradle was invoked on (taking command-line options such as --project-dir into account).
     */
    public File getCurrentDirectory() {
        return gradle.getStartParameter().getCurrentDir();
    }

    public File getGlobalScopeCacheDirectory() {
        return gradle.getGradleUserHomeDir();
    }

    public File getBuildScopeCacheDirectory() {
        return buildScopedCache.getRootDir();
    }

    /**
     * Is the build using an empty settings because a build definition is missing from the current directory?
     *
     * <p>There are two cases where this might be true: Gradle was invoked from a directory where there is no build script and no settings script in the directory hierarchy,
     * or Gradle was invoked from a directory where there is a settings script in the directory hierarchy but the directory is not a project directory for any project defined
     * in that settings script.</p>
     */
    public boolean isBuildDefinitionMissing() {
        boolean isNoBuildDefinitionFound = buildLayout.isBuildDefinitionMissing();
        boolean isCurrentDirNotPartOfContainingBuild = false;//radle.getSettings().getSettingsScript().getResource().getLocation().getFile() == null;
        return isNoBuildDefinitionFound || isCurrentDirNotPartOfContainingBuild;
    }
}