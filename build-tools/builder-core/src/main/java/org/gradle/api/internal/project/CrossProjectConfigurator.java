package org.gradle.api.internal.project;

import org.gradle.api.Action;
import org.gradle.api.BuildProject;

public interface CrossProjectConfigurator {

    void project(ProjectInternal project, Action<? super BuildProject> configureAction);

    void subprojects(Iterable<? extends ProjectInternal> projects, Action<? super BuildProject> configureAction);

    void allprojects(Iterable<? extends ProjectInternal> projects, Action<? super BuildProject> configureAction);

    void rootProject(ProjectInternal project, Action<? super BuildProject> buildOperationExecutor);

}
