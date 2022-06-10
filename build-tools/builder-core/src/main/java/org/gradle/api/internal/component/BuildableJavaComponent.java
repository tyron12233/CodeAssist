package org.gradle.api.internal.component;

import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;

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
