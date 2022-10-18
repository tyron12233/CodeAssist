package org.gradle.execution.plan;

import org.gradle.api.internal.tasks.WorkNodeAction;

import java.util.List;

public interface PostExecutionNodeAwareActionNode extends WorkNodeAction {
    List<? extends Node> getPostExecutionNodes();
}