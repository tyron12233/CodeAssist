package com.tyron.builder.api.plugins;

import com.tyron.builder.api.file.DirectoryProperty;

/**
 * <p>A {@link Convention} used for the BasePlugin.</p>
 *
 * @deprecated Use {@link BasePluginExtension} instead. This class is scheduled for removal in Gradle 8.0.
 */
@Deprecated
public abstract class BasePluginConvention {
    /**
     * Returns the directory to generate TAR and ZIP archives into.
     *
     * @return The directory. Never returns null.
     *
     * @since 6.0
     */
    public abstract DirectoryProperty getDistsDirectory();

    /**
     * Returns the directory to generate JAR and WAR archives into.
     *
     * @return The directory. Never returns null.
     *
     * @since 6.0
     */
    public abstract DirectoryProperty getLibsDirectory();

    /**
     * The name for the distributions directory. This in interpreted relative to the project' build directory.
     */
    public abstract String getDistsDirName();

    public abstract void setDistsDirName(String distsDirName);

    /**
     * The name for the libs directory. This in interpreted relative to the project' build directory.
     */
    public abstract String getLibsDirName();

    public abstract void setLibsDirName(String libsDirName);

    /**
     * The base name to use for archive files.
     */
    public abstract String getArchivesBaseName();

    public abstract void setArchivesBaseName(String archivesBaseName);
}
