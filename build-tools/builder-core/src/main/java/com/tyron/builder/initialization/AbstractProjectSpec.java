package com.tyron.builder.initialization;

import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.internal.project.ProjectRegistry;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractProjectSpec implements ProjectSpec {
    private static final String UNRELATED_BUILD_HINT = " If this is an unrelated build, it must have its own settings file.";
    @Override
    public boolean containsProject(ProjectRegistry<? extends ProjectIdentifier> registry) {
        checkPreconditions(registry);
        List<ProjectIdentifier> matches = new ArrayList<ProjectIdentifier>();
        select(registry, matches);
        return !matches.isEmpty();
    }

    @Override
    public <T extends ProjectIdentifier> T selectProject(String settingsDescription, ProjectRegistry<? extends T> registry) {
        checkPreconditions(registry);
        List<T> matches = new ArrayList<T>();
        select(registry, matches);
        if (matches.isEmpty()) {
            String message = formatNoMatchesMessage(settingsDescription) + UNRELATED_BUILD_HINT;
            throw new InvalidUserDataException(message);
        }
        if (matches.size() != 1) {
            throw new InvalidUserDataException(formatMultipleMatchesMessage(matches));
        }
        return matches.get(0);
    }

    protected void checkPreconditions(ProjectRegistry<?> registry) {
    }

    protected abstract String formatMultipleMatchesMessage(Iterable<? extends ProjectIdentifier> matches);

    protected abstract String formatNoMatchesMessage(String settings);

    protected abstract <T extends ProjectIdentifier> void select(ProjectRegistry<? extends T> candidates, List<? super T> matches);
}

