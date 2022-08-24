package org.gradle.execution.plan;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.tasks.WorkNodeAction;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

import java.util.IdentityHashMap;
import java.util.Map;

@ServiceScope(Scopes.Build.class)
public class WorkNodeDependencyResolver implements DependencyResolver {
    private final Map<WorkNodeAction, ActionNode> nodesForAction = new IdentityHashMap<WorkNodeAction, ActionNode>();

    @Override
    public boolean resolve(Task task, final Object node, Action<? super Node> resolveAction) {
        if (!(node instanceof WorkNodeAction)) {
            return false;
        }

        WorkNodeAction action = (WorkNodeAction) node;
        ActionNode actionNode = actionNodeFor(action);
        resolveAction.execute(actionNode);
        return true;
    }

    private ActionNode actionNodeFor(WorkNodeAction action) {
        ActionNode actionNode = nodesForAction.get(action);
        if (actionNode == null) {
            actionNode = new ActionNode(action);
            nodesForAction.put(action, actionNode);
        }
        return actionNode;
    }
}