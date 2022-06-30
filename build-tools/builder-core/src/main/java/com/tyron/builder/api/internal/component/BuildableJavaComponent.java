package com.tyron.builder.api.internal.component;

import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.file.FileCollection;

import java.util.Collection;

/**
 * Meta-info about a Java component.
 *
 * TODO - this is some legacy stuff, to be merged into other component interfaces
 */
public interface BuildableJavaComponent {
    Collection<String> getBuildTasks();

    FileCollection getRuntimeClasspath();

    Configuration getCompileDependencies();
}
