package org.gradle.plugins.ide.internal.configurer;

import org.gradle.api.Project;

public interface UniqueProjectNameProvider {
    String getUniqueName(Project project);
}
