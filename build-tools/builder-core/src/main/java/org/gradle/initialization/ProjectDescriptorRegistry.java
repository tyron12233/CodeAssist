package org.gradle.initialization;

import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.util.Path;

public interface ProjectDescriptorRegistry extends ProjectRegistry<DefaultProjectDescriptor> {
    void changeDescriptorPath(Path oldPath, Path newPath);
}