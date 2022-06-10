package org.gradle.api.file;

import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;

import java.io.File;

/**
 * Provides access to several important locations for a project.
 *
 * <p>An instance of this type can be injected into a task, plugin or other object by annotating a public constructor or method with {@code javax.inject.Inject}. It is also available via {@link org.gradle.api.Project#getLayout()}.
 *
 * @since 4.1
 */
@ServiceScope(Scopes.Project.class)
public interface ProjectLayout {
    /**
     * Returns the project directory.
     */
    Directory getProjectDirectory();

    /**
     * Returns the build directory for the project.
     */
    DirectoryProperty getBuildDirectory();

    /**
     * Creates a {@link RegularFile} provider whose location is calculated from the given {@link Provider}.
     */
    Provider<RegularFile> file(Provider<File> file);

    /**
     * Creates a {@link Directory} provider whose location is calculated from the given {@link Provider}.
     *
     * @since 6.0
     */
    Provider<Directory> dir(Provider<File> file);

    /**
     * <p>Creates a read-only {@link FileCollection} containing the given files, as defined by {@link Project#files(Object...)}.
     *
     * <p>This method can also be used to create an empty collection, but the collection may not be mutated later.</p>
     *
     * @param paths The paths to the files. May be empty.
     * @return The file collection. Never returns null.
     * @since 4.8
     */
    FileCollection files(Object... paths);
}

