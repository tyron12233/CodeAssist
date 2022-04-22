package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.util.Path;

public interface ProjectDescriptorRegistry extends ProjectRegistry<DefaultProjectDescriptor> {
    void changeDescriptorPath(Path oldPath, Path newPath);
}