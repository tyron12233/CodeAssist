package com.tyron.completion.index;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.Module;

public abstract class CompilerProvider<T> {

    public abstract T get(Project project, Module module);
}
