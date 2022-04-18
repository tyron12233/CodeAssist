package com.tyron.builder.initialization;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.internal.project.ProjectRegistry;

import java.io.File;

import java.util.List;

public class ProjectDirectoryProjectSpec extends AbstractProjectSpec {
    private final File dir;

    public ProjectDirectoryProjectSpec(File dir) {
        this.dir = dir;
    }

    @Override
    protected String formatNoMatchesMessage(String settings) {
        return String.format("Project directory '%s' is not part of the build defined by %s.", dir, settings);
    }

    @Override
    protected String formatMultipleMatchesMessage(Iterable<? extends ProjectIdentifier> matches) {
        return String.format("Multiple projects in this build have project directory '%s': %s", dir, matches);
    }

    @Override
    protected <T extends ProjectIdentifier> void select(ProjectRegistry<? extends T> candidates, List<? super T> matches) {
        for (T candidate : candidates.getAllProjects()) {
            if (candidate.getProjectDir().equals(dir)) {
                matches.add(candidate);
            }
        }
    }

    @Override
    protected void checkPreconditions(ProjectRegistry<?> registry) {
        if (!dir.exists()) {
            throw new InvalidUserDataException(String.format("Project directory '%s' does not exist.", dir));
        }
        if (!dir.isDirectory()) {
            throw new InvalidUserDataException(String.format("Project directory '%s' is not a directory.", dir));
        }
    }
}
