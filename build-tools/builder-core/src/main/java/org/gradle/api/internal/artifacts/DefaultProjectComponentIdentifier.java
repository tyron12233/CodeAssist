package org.gradle.api.internal.artifacts;

import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.util.Path;

public class DefaultProjectComponentIdentifier implements ProjectComponentIdentifier {
    private final BuildIdentifier buildIdentifier;
    private final Path projectPath;
    private final Path identityPath;
    private final String projectName;
    private String displayName;

    public DefaultProjectComponentIdentifier(BuildIdentifier buildIdentifier, Path identityPath, Path projectPath, String projectName) {
        assert buildIdentifier != null : "build cannot be null";
        assert identityPath != null : "identity path cannot be null";
        assert projectPath != null : "project path cannot be null";
        assert projectName != null : "project name cannot be null";
        this.identityPath = identityPath;
        this.projectName = projectName;
        this.buildIdentifier = buildIdentifier;
        this.projectPath = projectPath;
    }

    @Override
    public String getDisplayName() {
        if (displayName == null) {
            displayName = "project " + identityPath.getPath();
        }
        return displayName;
    }

    @Override
    public BuildIdentifier getBuild() {
        return buildIdentifier;
    }

    public Path getIdentityPath() {
        return identityPath;
    }

    @Override
    public String getProjectPath() {
        return projectPath.getPath();
    }

    public Path projectPath() {
        return projectPath;
    }

    @Override
    public String getProjectName() {
        return projectName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultProjectComponentIdentifier that = (DefaultProjectComponentIdentifier) o;
        return identityPath.equals(that.identityPath);
    }

    @Override
    public int hashCode() {
        return identityPath.hashCode();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}