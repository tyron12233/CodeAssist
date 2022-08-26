package org.gradle.plugins.ide.idea.internal;

import org.gradle.api.Task;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.gradle.plugins.ide.idea.model.IdeaModule;
import org.gradle.plugins.ide.internal.IdeProjectMetadata;

import java.io.File;
import java.util.Collections;
import java.util.Set;

public class IdeaModuleMetadata implements IdeProjectMetadata {
    private final IdeaModule ideaModule;
    private final TaskProvider<? extends Task> generatorTask;

    public IdeaModuleMetadata(IdeaModule ideaModule, TaskProvider<? extends Task> generatorTask) {
        this.ideaModule = ideaModule;
        this.generatorTask = generatorTask;
    }

    @Override
    public DisplayName getDisplayName() {
        return Describables.withTypeAndName("IDEA module", ideaModule.getName());
    }

    public String getName() {
        return ideaModule.getName();
    }

    @Override
    public File getFile() {
        return ideaModule.getOutputFile();
    }

    @Override
    public Set<? extends Task> getGeneratorTasks() {
        return Collections.singleton(generatorTask.get());
    }
}
