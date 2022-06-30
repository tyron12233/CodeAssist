package com.tyron.builder.execution.taskpath;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.util.NameMatcher;

import org.slf4j.Logger;

import java.util.Map;

public class ProjectFinderByTaskPath {
    private static final Logger LOGGER = Logging.getLogger(ProjectFinderByTaskPath.class);

    /**
     * Resolves a project defined by {@code projectPath}.
     * <p>
     * Depending on the path, resolution can be relative, or absolute:
     * <ul>
     *     <li>If the path start with {@link BuildProject#PATH_SEPARATOR}, the resolution starts from the root project</li>
     *     <li>If there is no start separator, resolution is starting from {@code startProject}</li>
     * </ul>
     *
     * @param projectPath a project path, optionally started/segmented by {@link BuildProject#PATH_SEPARATOR}
     * @param startProject the project, where a relative resolution starts from
     * @return the project addressed by {@code projectPath}
     * @throws ProjectLookupException if any of the intermediate projects cannot be found
     */
    public ProjectInternal findProject(String projectPath, ProjectInternal startProject) throws ProjectLookupException {
        // We save the original path, as we are going to mangle the variable
        String originalProjectPath = projectPath;
        MatchedProject matchedProject;

        if (projectPath.startsWith(BuildProject.PATH_SEPARATOR)) {
            // If a path start with a separator, we handle it as an absolute path,
            // and get the root project instead of the current project
            matchedProject = new MatchedProject(startProject.getRootProject(), false);
            // As we already got the root project, we should handle the path as a relative one,
            // by cutting the path separator off
            projectPath = projectPath.substring(1);
        } else {
            matchedProject = new MatchedProject(startProject, false);
        }

        String[] components = projectPath.split(BuildProject.PATH_SEPARATOR);
        for (final String component : components) {
            // Should be checked, as split above can cause troubles,
            // if the input is only an empty string
            if (!component.isEmpty()) {
                // Descend, and try to resolve the child project
                matchedProject = resolveProject(component, matchedProject);
            }
        }
        if (matchedProject.isPatternMatched()) {
            LOGGER.info("Abbreviated project name '{}' matched '{}'", originalProjectPath, matchedProject.getProject().getPath());
        } else {
            LOGGER.info("Project name matched '{}'", matchedProject.getProject().getPath());
        }

        return (ProjectInternal) matchedProject.getProject();
    }

    /**
     * Tries to resolve {@code projectName} inside {@code parentProjectMatch}.
     * <p>
     * This method tries to look for exact, and if not found, abbreviated projects
     * inside the {@code parentProjectMatch}.
     *
     * @param projectName an exact or abbreviated project name
     * @param parentProjectMatch the parent's project match
     * @return a new match with the looked up project
     * @throws ProjectLookupException if the project cannot be found in the parent project
     */
    private MatchedProject resolveProject(String projectName, MatchedProject parentProjectMatch) throws ProjectLookupException {
        BuildProject parentProject = parentProjectMatch.getProject();
        Map<String, BuildProject> childProjects = parentProject.getChildProjects();

        if (childProjects.containsKey(projectName)) {
            return new MatchedProject(childProjects.get(projectName), parentProjectMatch.isPatternMatched());
        } else {
            NameMatcher matcher = new NameMatcher();
            BuildProject foundProject = matcher.find(projectName, childProjects);
            if (foundProject != null) {
                return new MatchedProject(foundProject, true);
            } else {
                throw new ProjectLookupException(matcher.formatErrorMessage("project", parentProject));
            }
        }
    }

    public static class ProjectLookupException extends InvalidUserDataException {
        public ProjectLookupException(String message) {
            super(message);
        }
    }

    /**
     * Utility class storing information about a project resolution.
     */
    private static class MatchedProject {
        private final BuildProject project;
        private final boolean patternMatched;

        public MatchedProject(BuildProject project, boolean patternMatched) {
            this.project = project;
            this.patternMatched = patternMatched;
        }

        /**
         * The currently resolved project
         */
        public BuildProject getProject() {
            return project;
        }

        /**
         * Marks if in the chain of resolutions, pattern matching was necessary
         */
        public boolean isPatternMatched() {
            return patternMatched;
        }
    }
}
