package com.tyron.builder.launcher.exec;

import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.invocation.BuildAction;
import com.tyron.builder.internal.build.BuildLayoutValidator;
import com.tyron.builder.internal.buildtree.BuildActionModelRequirements;
import com.tyron.builder.internal.buildtree.BuildActionRunner;
import com.tyron.builder.internal.buildtree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.buildtree.RunTasksRequirements;
import com.tyron.builder.internal.session.BuildSessionActionExecutor;
import com.tyron.builder.internal.session.BuildSessionContext;

/**
 * A {@link BuildActionExecuter} responsible for establishing the build tree for a single invocation of a {@link BuildAction}.
 */
public class BuildTreeLifecycleBuildActionExecutor implements BuildSessionActionExecutor {
    private final BuildTreeModelControllerServices buildTreeModelControllerServices;
    private final BuildLayoutValidator buildLayoutValidator;

    public BuildTreeLifecycleBuildActionExecutor(BuildTreeModelControllerServices buildTreeModelControllerServices, BuildLayoutValidator buildLayoutValidator) {
        this.buildTreeModelControllerServices = buildTreeModelControllerServices;
        this.buildLayoutValidator = buildLayoutValidator;
    }

    @Override
    public BuildActionRunner.Result execute(BuildAction action, BuildSessionContext buildSession) {
        BuildActionRunner.Result result = null;
        try {
            buildLayoutValidator.validate(action.getStartParameter());

            BuildActionModelRequirements actionRequirements;
//            if (action instanceof BuildModelAction && action.isCreateModel()) {
//                BuildModelAction buildModelAction = (BuildModelAction) action;
//                actionRequirements = new QueryModelRequirements(action.getStartParameter(), action.isRunTasks(), buildModelAction.getModelName());
//            } else if (action instanceof ClientProvidedBuildAction) {
//                actionRequirements = new RunActionRequirements(action.getStartParameter(), action.isRunTasks());
//            } else if (action instanceof ClientProvidedPhasedAction) {
//                actionRequirements = new RunPhasedActionRequirements(action.getStartParameter(), action.isRunTasks());
//            } else {
                actionRequirements = new RunTasksRequirements(action.getStartParameter());
//            }
            BuildTreeModelControllerServices.Supplier modelServices = buildTreeModelControllerServices.servicesForBuildTree(actionRequirements);
            BuildTreeState buildTree = new BuildTreeState(buildSession.getServices(), modelServices);
            try {
                result = buildTree.run(context -> context.execute(action));
            } finally {
                buildTree.close();
            }
        } catch (Throwable t) {
            if (result == null) {
                // Did not create a result
                // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
                // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
                throw UncheckedException.throwAsUncheckedException(t);
            } else {
                // Cleanup has failed, combine the cleanup failure with other failures that may be packed in the result
                // Note: throw the failure rather than returning a result object containing the failure, as console failure logging based on the _result_ happens down in the root build scope
                // whereas console failure logging based on the _thrown exception_ happens up outside session scope. It would be better to refactor so that a result can be returned from here
                throw UncheckedException.throwAsUncheckedException(result.addFailure(t).getBuildFailure());
            }
        }
        return result;
    }
}
