package com.tyron.builder.api.artifacts;

import groovy.lang.Closure;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.NamedDomainObjectList;
import com.tyron.builder.api.artifacts.repositories.ArtifactRepository;
import com.tyron.builder.util.Configurable;

/**
 * <p>A {@code ResolverContainer} is responsible for managing a set of {@link ArtifactRepository} instances. Repositories are arranged in a sequence.</p>
 *
 * <p>You can obtain a {@code ResolverContainer} instance by calling {@link com.tyron.builder.api.Project#getRepositories()} or
 * using the {@code repositories} property in your build script.</p>
 *
 * <p>The resolvers in a container are accessible as read-only properties of the container, using the name of the
 * resolver as the property name. For example:</p>
 *
 * <pre class='autoTested'>
 * repositories.maven { name 'myResolver' }
 * repositories.myResolver.url = 'some-url'
 * </pre>
 *
 * <p>A dynamic method is added for each resolver which takes a configuration closure. This is equivalent to calling
 * {@link #getByName(String, groovy.lang.Closure)}. For example:</p>
 *
 * <pre class='autoTested'>
 * repositories.maven { name 'myResolver' }
 * repositories.myResolver {
 *     url 'some-url'
 * }
 * </pre>
 */
public interface ArtifactRepositoryContainer extends NamedDomainObjectList<ArtifactRepository>, Configurable<ArtifactRepositoryContainer> {
    String DEFAULT_MAVEN_CENTRAL_REPO_NAME = "MavenRepo";
    String DEFAULT_MAVEN_LOCAL_REPO_NAME = "MavenLocal";
    String MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2/";
    String GOOGLE_URL = "https://dl.google.com/dl/android/maven2/";

    /**
     * Adds a repository to this container, at the end of the repository sequence.
     *
     * @param repository The repository to add.
     */
    @Override
    boolean add(ArtifactRepository repository);

    /**
     * Adds a repository to this container, at the start of the repository sequence.
     *
     * @param repository The repository to add.
     */
    void addFirst(ArtifactRepository repository);

    /**
     * Adds a repository to this container, at the end of the repository sequence.
     *
     * @param repository The repository to add.
     */
    void addLast(ArtifactRepository repository);

    /**
     * {@inheritDoc}
     */
    @Override
    ArtifactRepository getByName(String name) throws UnknownRepositoryException;

    /**
     * {@inheritDoc}
     */
    @Override
    ArtifactRepository getByName(String name, Closure configureClosure) throws UnknownRepositoryException;

    /**
     * {@inheritDoc}
     */
    @Override
    ArtifactRepository getByName(String name, Action<? super ArtifactRepository> configureAction) throws UnknownRepositoryException;

    /**
     * {@inheritDoc}
     */
    @Override
    ArtifactRepository getAt(String name) throws UnknownRepositoryException;
}
