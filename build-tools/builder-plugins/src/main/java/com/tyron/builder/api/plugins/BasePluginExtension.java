package com.tyron.builder.api.plugins;

import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.provider.Property;

/**
 * An extension used for {@link BasePlugin}.
 * <p>
 * Replaces {@link BasePluginConvention}.
 *
 * @since 7.1
 */
public interface BasePluginExtension {
    /**
     * Returns the directory to generate TAR and ZIP archives into.
     *
     * @return The directory. Never returns null.
     */
    DirectoryProperty getDistsDirectory();

    /**
     * Returns the directory to generate JAR and WAR archives into.
     *
     * @return The directory. Never returns null.
     */
    DirectoryProperty getLibsDirectory();

    /**
     * The base name to use for archive files.
     */
    Property<String> getArchivesName();

    /**
     * This method is only here to maintain compatibility with existing builds.
     *
     * @deprecated Use {@link #getDistsDirectory()}. This method is scheduled for removal in Gradle 8.0.
     */
    @Deprecated
    String getDistsDirName();

    /**
     * This method is only here to maintain compatibility with existing builds.
     *
     * @deprecated Use {@link #getDistsDirectory()}. This method is scheduled for removal in Gradle 8.0.
     */
    @Deprecated
    void setDistsDirName(String distsDirName);

    /**
     * This method is only here to maintain compatibility with existing builds.
     *
     * @deprecated Use {@link #getLibsDirectory()}. This method is scheduled for removal in Gradle 8.0.
     */
    @Deprecated
    String getLibsDirName();

    /**
     * This method is only here to maintain compatibility with existing builds.
     *
     * @deprecated Use {@link #getLibsDirectory()}. This method is scheduled for removal in Gradle 8.0.
     */
    @Deprecated
    void setLibsDirName(String libsDirName);

    /**
     * This method is only here to maintain compatibility with existing builds.
     *
     * @deprecated Use {@link #getArchivesName()}. This method is scheduled for removal in Gradle 8.0.
     */
    @Deprecated
    String getArchivesBaseName();

    /**
     * This method is only here to maintain compatibility with existing builds.
     *
     * @deprecated Use {@link #getArchivesName()}. This method is scheduled for removal in Gradle 8.0.
     */
    @Deprecated
    void setArchivesBaseName(String archivesBaseName);
}
