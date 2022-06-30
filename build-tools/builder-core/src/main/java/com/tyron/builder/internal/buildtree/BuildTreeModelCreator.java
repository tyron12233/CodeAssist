package com.tyron.builder.internal.buildtree;

/**
 * Responsible for creating a model from the build tree model.
 */
public interface BuildTreeModelCreator {
    <T> void beforeTasks(BuildTreeModelAction<? extends T> action);

    <T> T fromBuildModel(BuildTreeModelAction<? extends T> action);
}