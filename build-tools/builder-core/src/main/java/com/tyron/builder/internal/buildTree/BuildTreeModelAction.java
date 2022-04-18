package com.tyron.builder.internal.buildTree;

public interface BuildTreeModelAction<T> {
    void beforeTasks(BuildTreeModelController controller);

    T fromBuildModel(BuildTreeModelController controller);
}