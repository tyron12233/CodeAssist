package com.tyron.builder.initialization;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.internal.project.ProjectRegistry;

import java.io.File;
import java.util.List;

public class BuildFileProjectSpec extends AbstractProjectSpec {
    private final File buildFile;

    public BuildFileProjectSpec(File buildFile) {
        this.buildFile = buildFile;
    }

    @Override
    protected String formatNoMatchesMessage(String settings) {
        return String.format("Build file '%s' is not part of the build defined by %s.", buildFile, settings);
    }

    @Override
    protected String formatMultipleMatchesMessage(Iterable<? extends ProjectIdentifier> matches) {
        return String.format("Multiple projects in this build have build file '%s': %s", buildFile, matches);
    }

    @Override
    protected <T extends ProjectIdentifier> void select(ProjectRegistry<? extends T> candidates, List<? super T> matches) {
        for (T candidate : candidates.getAllProjects()) {
            if (candidate.getBuildFile().equals(buildFile)) {
                matches.add(candidate);
            }
        }
    }

    @Override
    protected void checkPreconditions(ProjectRegistry<?> registry) {
        if (!buildFile.exists()) {
            throw new InvalidUserDataException(String.format("Build file '%s' does not exist.", buildFile));
        }
        if (!buildFile.isFile()) {
            throw new InvalidUserDataException(String.format("Build file '%s' is not a file.", buildFile));
        }
    }
}
