package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.project.ProjectIdentifier;
import com.tyron.builder.api.internal.project.ProjectRegistry;

import java.io.File;
import java.util.List;

public class CurrentDirectoryProjectSpec extends AbstractProjectSpec {
    private final boolean useRootWhenNoMatch;
    private final File currentDir;

    public CurrentDirectoryProjectSpec(File currentDir, SettingsInternal settings) {
        this.currentDir = currentDir;
        this.useRootWhenNoMatch = currentDir.equals(settings.getSettingsDir());
    }

    @Override
    protected <T extends ProjectIdentifier> void select(ProjectRegistry<? extends T> candidates, List<? super T> matches) {
        for (T candidate : candidates.getAllProjects()) {
            if (candidate.getProjectDir().equals(currentDir)) {
                matches.add(candidate);
            }
        }
        if (useRootWhenNoMatch && matches.isEmpty()) {
            matches.add(candidates.getRootProject());
        }
    }

    @Override
    protected String formatNoMatchesMessage(String settings) {
        return String.format("Project directory '%s' is not part of the build defined by %s.",  currentDir, settings);
    }

    @Override
    protected String formatMultipleMatchesMessage(Iterable<? extends ProjectIdentifier> matches) {
        return String.format("Multiple projects in this build have project directory '%s': %s", currentDir, matches);
    }
}