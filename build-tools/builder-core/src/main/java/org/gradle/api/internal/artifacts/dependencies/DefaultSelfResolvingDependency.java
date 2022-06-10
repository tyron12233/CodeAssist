package org.gradle.api.internal.artifacts.dependencies;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.artifacts.DependencyResolveContext;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.tasks.TaskDependency;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Set;

public class DefaultSelfResolvingDependency extends AbstractDependency implements SelfResolvingDependencyInternal, FileCollectionDependency {
    private final ComponentIdentifier targetComponentId;
    private final FileCollectionInternal source;

    public DefaultSelfResolvingDependency(FileCollectionInternal source) {
        this.targetComponentId = null;
        this.source = source;
    }

    public DefaultSelfResolvingDependency(ComponentIdentifier targetComponentId, FileCollectionInternal source) {
        this.targetComponentId = targetComponentId;
        this.source = source;
    }

    @Override
    public boolean contentEquals(Dependency dependency) {
        if (!(dependency instanceof DefaultSelfResolvingDependency)) {
            return false;
        }
        DefaultSelfResolvingDependency selfResolvingDependency = (DefaultSelfResolvingDependency) dependency;
        return source.equals(selfResolvingDependency.source);
    }

    @Override
    public DefaultSelfResolvingDependency copy() {
        return new DefaultSelfResolvingDependency(targetComponentId, source);
    }

    @Nullable
    @Override
    public ComponentIdentifier getTargetComponentId() {
        return targetComponentId;
    }

    @Override
    public String getGroup() {
        return null;
    }

    @Override
    public String getName() {
        return "unspecified";
    }

    @Override
    public String getVersion() {
        return null;
    }

    @Override
    public void resolve(DependencyResolveContext context) {
        context.add(source);
    }

    @Override
    public Set<File> resolve() {
        return source.getFiles();
    }

    @Override
    public Set<File> resolve(boolean transitive) {
        return source.getFiles();
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return source.getBuildDependencies();
    }

    @Override
    public FileCollection getFiles() {
        return source;
    }

}
