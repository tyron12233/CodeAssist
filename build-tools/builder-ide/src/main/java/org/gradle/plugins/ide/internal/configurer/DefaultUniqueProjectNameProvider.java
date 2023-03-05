package org.gradle.plugins.ide.internal.configurer;

import org.gradle.api.Project;
import org.gradle.api.internal.project.ProjectState;
import org.gradle.api.internal.project.ProjectStateRegistry;

import java.util.Map;

public class DefaultUniqueProjectNameProvider implements UniqueProjectNameProvider {
    private final ProjectStateRegistry projectRegistry;
    private Map<ProjectState, String> deduplicated;

    public DefaultUniqueProjectNameProvider(ProjectStateRegistry projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    @Override
    public String getUniqueName(Project project) {
        ProjectState projectState = projectRegistry.stateFor(project);
        String uniqueName = getDeduplicatedNames().get(projectState);
        if (uniqueName != null) {
            return uniqueName;
        }
        return project.getName();
    }

    private synchronized Map<ProjectState, String> getDeduplicatedNames() {
        if (deduplicated == null) {
            HierarchicalElementDeduplicator<ProjectState> deduplicator = new HierarchicalElementDeduplicator<ProjectState>(new ProjectPathDeduplicationAdapter());
            this.deduplicated = deduplicator.deduplicate(projectRegistry.getAllProjects());
        }
        return deduplicated;
    }

    private static class ProjectPathDeduplicationAdapter implements HierarchicalElementAdapter<ProjectState> {
        @Override
        public String getName(ProjectState element) {
            return element.getName();
        }

        @Override
        public String getIdentityName(ProjectState element) {
            String identityName = element.getIdentityPath().getName();
            return identityName != null ? identityName : element.getName();
        }

        @Override
        public ProjectState getParent(ProjectState element) {
            return element.getParent();
        }
    }
}
