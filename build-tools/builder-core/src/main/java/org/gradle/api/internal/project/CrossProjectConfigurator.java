package org.gradle.api.internal.project;

import org.gradle.api.Action;
import org.gradle.api.Project;

public interface CrossProjectConfigurator {

    void project(ProjectInternal project, Action<? super Project> configureAction);

    void subprojects(Iterable<? extends ProjectInternal> projects, Action<? super Project> configureAction);

    void allprojects(Iterable<? extends ProjectInternal> projects, Action<? super Project> configureAction);

    void rootProject(ProjectInternal project, Action<? super Project> buildOperationExecutor);

}
