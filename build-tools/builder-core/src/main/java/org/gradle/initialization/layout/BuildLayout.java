package org.gradle.initialization.layout;

import static org.gradle.initialization.DefaultProjectDescriptor.BUILD_SCRIPT_BASENAME;

import org.gradle.initialization.SettingsLocation;
import org.gradle.internal.scripts.ScriptFileResolver;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public class BuildLayout extends SettingsLocation {
    private final File rootDirectory;
    private final ScriptFileResolver scriptFileResolver;

    // Note: `null` for `settingsFile` means explicitly no settings
    //       A non null value can be a non existent file, which is semantically equivalent to an empty file
    public BuildLayout(File rootDirectory, File settingsDir, @Nullable File settingsFile, ScriptFileResolver scriptFileResolver) {
        super(settingsDir, settingsFile);
        this.rootDirectory = rootDirectory;
        this.scriptFileResolver = scriptFileResolver;
    }

    /**
     * Was a build definition found?
     */
    public boolean isBuildDefinitionMissing() {
        return getSettingsFile() != null && !getSettingsFile().exists() && scriptFileResolver.resolveScriptFile(getRootDirectory(), BUILD_SCRIPT_BASENAME) == null;
    }

    /**
     * Returns the root directory of the build, is never null.
     */
    public File getRootDirectory() {
        return rootDirectory;
    }
}