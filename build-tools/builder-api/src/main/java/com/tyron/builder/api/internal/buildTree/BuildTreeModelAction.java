package com.tyron.builder.api.internal.buildTree;

public interface BuildTreeModelAction<T> {
    void beforeTasks(BuildTreeModelController controller);

    T fromBuildModel(BuildTreeModelController controller);
}