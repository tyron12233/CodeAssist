package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;

public interface CrossProjectConfigurator {

    void project(ProjectInternal project, Action<? super BuildProject> configureAction);

    void subprojects(Iterable<? extends ProjectInternal> projects, Action<? super BuildProject> configureAction);

    void allprojects(Iterable<? extends ProjectInternal> projects, Action<? super BuildProject> configureAction);

    void rootProject(ProjectInternal project, Action<? super BuildProject> buildOperationExecutor);

}
