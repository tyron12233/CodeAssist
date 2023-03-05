package org.gradle.plugins.ide.internal.resolver;

import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;

import java.io.File;
import java.util.Set;

/**
 * Used in conjunction with {@link IdeDependencySet} to adapt Gradle's dependency resolution API to the
 * specific needs of the IDE plugins.
 */
public interface IdeDependencyVisitor {
    /**
     * If true, external dependencies will be skipped.
     */
    boolean isOffline();

    /**
     * Should sources for external dependencies be downloaded?
     */
    boolean downloadSources();

    /**
     * Should javadoc for external dependencies be downloaded?
     */
    boolean downloadJavaDoc();

    /**
     * The dependency points to an artifact built by another project.
     * The component identifier is guaranteed to be a {@link org.gradle.api.artifacts.component.ProjectComponentIdentifier}.
     */
    void visitProjectDependency(ResolvedArtifactResult artifact, boolean testDependency, boolean asJavaModule);

    /**
     * The dependency points to an external module.
     * The component identifier is guaranteed to be a {@link org.gradle.api.artifacts.component.ModuleComponentIdentifier}.
     * The source and javadoc locations maybe be empty, but never null.
     */
    void visitModuleDependency(ResolvedArtifactResult artifact, Set<ResolvedArtifactResult> sources, Set<ResolvedArtifactResult> javaDoc, boolean testDependency, boolean asJavaModule);

    /**
     * The dependency points neither to a project, nor an external module, so this method should treat it as an opaque file.
     */
    void visitFileDependency(ResolvedArtifactResult artifact, boolean testDependency);

    /**
     * A generated file dependency to which we might be able to attach sources
     */
    void visitGradleApiDependency(ResolvedArtifactResult artifact, File sources, boolean testDependency);

    /**
     * There was an unresolved dependency in the result.
     */
    void visitUnresolvedDependency(UnresolvedDependencyResult unresolvedDependency);
}
