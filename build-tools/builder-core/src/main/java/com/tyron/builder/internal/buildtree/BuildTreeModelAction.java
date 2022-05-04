package com.tyron.builder.internal.buildtree;

public interface BuildTreeModelAction<T> {
    void beforeTasks(BuildTreeModelController controller);

    T fromBuildModel(BuildTreeModelController controller);
}