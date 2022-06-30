package com.tyron.builder.initialization.layout;

import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.internal.service.scopes.Scope;
import com.tyron.builder.internal.service.scopes.ServiceScope;
import com.tyron.builder.api.resources.MissingResourceException;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.internal.scripts.DefaultScriptFileResolver;

import javax.annotation.Nullable;
import java.io.File;

@ServiceScope(Scope.Global.class)
public class BuildLayoutFactory {

    private static final String DEFAULT_SETTINGS_FILE_BASENAME = "settings";
    private final DefaultScriptFileResolver scriptFileResolver = new DefaultScriptFileResolver();

    /**
     * Determines the layout of the build, given a current directory and some other configuration.
     */
    public BuildLayout getLayoutFor(File currentDir, boolean shouldSearchUpwards) {
        boolean searchUpwards = shouldSearchUpwards && !isBuildSrc(currentDir);
        return getLayoutFor(currentDir, searchUpwards ? null : currentDir.getParentFile());
    }

    private boolean isBuildSrc(File currentDir) {
        return currentDir.getName().equals(SettingsInternal.BUILD_SRC);
    }

    /**
     * Determines the layout of the build, given a current directory and some other configuration.
     */
    public BuildLayout getLayoutFor(BuildLayoutConfiguration configuration) {
        if (configuration.isUseEmptySettings()) {
            return buildLayoutFrom(configuration, null);
        }
        File explicitSettingsFile = configuration.getSettingsFile();
        if (explicitSettingsFile != null) {
            if (!explicitSettingsFile.isFile()) {
                throw new MissingResourceException(explicitSettingsFile.toURI(), String.format("Could not read settings file '%s' as it does not exist.", explicitSettingsFile.getAbsolutePath()));
            }
            return buildLayoutFrom(configuration, explicitSettingsFile);
        }

        return getLayoutFor(configuration.getCurrentDir(), configuration.isSearchUpwards());
    }

    private BuildLayout buildLayoutFrom(BuildLayoutConfiguration configuration, File settingsFile) {
        return new BuildLayout(configuration.getCurrentDir(), configuration.getCurrentDir(), settingsFile, scriptFileResolver);
    }

    @Nullable
    public File findExistingSettingsFileIn(File directory) {
        return scriptFileResolver.resolveScriptFile(directory, DEFAULT_SETTINGS_FILE_BASENAME);
    }

    BuildLayout getLayoutFor(File currentDir, File stopAt) {
        File settingsFile = findExistingSettingsFileIn(currentDir);
        if (settingsFile != null) {
            return layout(currentDir, settingsFile);
        }
        for (File candidate = currentDir.getParentFile(); candidate != null && !candidate.equals(stopAt); candidate = candidate.getParentFile()) {
            settingsFile = findExistingSettingsFileIn(candidate);
            if (settingsFile != null) {
                return layout(candidate, settingsFile);
            }
        }
        return layout(currentDir, new File(currentDir, Settings.DEFAULT_SETTINGS_FILE));
    }

    private BuildLayout layout(File rootDir, File settingsFile) {
        return new BuildLayout(rootDir, settingsFile.getParentFile(), GFileUtils.canonicalize(settingsFile), scriptFileResolver);
    }
}