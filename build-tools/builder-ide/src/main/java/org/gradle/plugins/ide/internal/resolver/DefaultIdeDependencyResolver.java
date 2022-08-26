package org.gradle.plugins.ide.internal.resolver;

import org.gradle.api.Action;
import org.gradle.api.artifacts.ArtifactView;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier;
import org.gradle.api.specs.Spec;
import org.gradle.plugins.ide.internal.resolver.model.IdeExtendedRepoFileDependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * TODO only here because Kotlin DSL uses this. Please remove once that is fixed.
 */
public class DefaultIdeDependencyResolver {

    private static final Spec<ComponentIdentifier> IS_A_MODULE_ID = new Spec<ComponentIdentifier>() {
        @Override
        public boolean isSatisfiedBy(ComponentIdentifier id) {
            return id instanceof ModuleComponentIdentifier;
        }
    };

    public List<IdeExtendedRepoFileDependency> getIdeRepoFileDependencies(Configuration configuration) {
        Set<ResolvedArtifactResult> artifacts = configuration.getIncoming().artifactView(new Action<ArtifactView.ViewConfiguration>() {
            @Override
            public void execute(ArtifactView.ViewConfiguration viewConfiguration) {
                viewConfiguration.lenient(true);
                viewConfiguration.componentFilter(IS_A_MODULE_ID);
            }
        }).getArtifacts().getArtifacts();

        List<IdeExtendedRepoFileDependency> externalDependencies = new ArrayList<IdeExtendedRepoFileDependency>(artifacts.size());
        for (ResolvedArtifactResult artifact : artifacts) {
            ModuleComponentIdentifier moduleId = (ModuleComponentIdentifier) artifact.getId().getComponentIdentifier();
            IdeExtendedRepoFileDependency ideRepoFileDependency = new IdeExtendedRepoFileDependency();
            ideRepoFileDependency.setId(DefaultModuleVersionIdentifier.newId(moduleId.getModuleIdentifier(), moduleId.getVersion()));
            externalDependencies.add(ideRepoFileDependency);
        }

        return externalDependencies;
    }

}