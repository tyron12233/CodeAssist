package org.gradle.api.artifacts.dsl;

import org.gradle.api.Incubating;
import org.gradle.api.NonExtensible;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.file.FileCollection;
import org.gradle.internal.HasInternalProtocol;

import javax.annotation.Nullable;

/**
 * Factory class for creating {@link Dependency} instances, with strong typing.
 *
 * <p>
 * An instance of the factory can be injected into a task, plugin or other object by annotating a public constructor or property getter method with {@code javax.inject.Inject}.
 * It is also available via {@link Project#getDependencyFactory()}.
 * </p>
 *
 * <p>
 * <b>Note:</b> This interface is not intended for implementation by build script or plugin authors.
 * </p>
 *
 * @since 7.6
 */
@HasInternalProtocol
@NonExtensible
@Incubating
public interface DependencyFactory {
    /**
     * Create an {@link ExternalModuleDependency} from the <code>"<i>group</i>:<i>name</i>:<i>version</i>:<i>classifier</i>@<i>extension</i>"</code> notation.
     *
     * <p>
     * Classifier and extension may each separately be omitted. Version may be omitted if there is no classifier.
     * </p>
     *
     * @param dependencyNotation the dependency notation
     * @return the new dependency
     */
    ExternalModuleDependency create(CharSequence dependencyNotation);

    /**
     * Create an {@link ExternalModuleDependency} from a series of strings.
     *
     * @param group the group
     * @param name the name
     * @param version the version
     * @return the new dependency
     */
    ExternalModuleDependency create(@Nullable String group, String name, @Nullable String version);

    /**
     * Create an {@link ExternalModuleDependency} from a series of strings.
     *
     * @param group the group
     * @param name the name
     * @param version the version
     * @param classifier the classifier
     * @param extension the extension
     * @return the new dependency
     */
    ExternalModuleDependency create(@Nullable String group, String name, @Nullable String version, @Nullable String classifier, @Nullable String extension);

    /**
     * Create a {@link FileCollectionDependency} from a {@link FileCollection}.
     *
     * @param fileCollection the file collection
     * @return the new dependency
     */
    FileCollectionDependency create(FileCollection fileCollection);

    /**
     * Create a {@link ProjectDependency} from a {@link Project}.
     *
     * @param project the project
     * @return the new dependency
     */
    ProjectDependency create(Project project);
}
