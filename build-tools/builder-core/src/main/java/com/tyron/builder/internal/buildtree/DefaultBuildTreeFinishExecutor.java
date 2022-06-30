package com.tyron.builder.internal.buildtree;

import com.tyron.builder.initialization.exception.ExceptionAnalyser;
import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.ExecutionResult;
import com.tyron.builder.internal.build.NestedBuildState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class DefaultBuildTreeFinishExecutor implements BuildTreeFinishExecutor {
    private final BuildStateRegistry buildStateRegistry;
    private final ExceptionAnalyser exceptionAnalyser;
    private final BuildLifecycleController buildLifecycleController;

    public DefaultBuildTreeFinishExecutor(
            BuildStateRegistry buildStateRegistry,
            ExceptionAnalyser exceptionAnalyser,
            BuildLifecycleController buildLifecycleController
    ) {
        this.buildStateRegistry = buildStateRegistry;
        this.exceptionAnalyser = exceptionAnalyser;
        this.buildLifecycleController = buildLifecycleController;
    }

    @Override
    @Nullable
    public RuntimeException finishBuildTree(List<Throwable> failures) {
        List<Throwable> allFailures = new ArrayList<>(failures);
        buildStateRegistry.visitBuilds(buildState -> {
            if (buildState instanceof NestedBuildState) {
                ExecutionResult<Void> result = ((NestedBuildState) buildState).finishBuild();
                allFailures.addAll(result.getFailures());
            }
        });
        RuntimeException reportableFailure = exceptionAnalyser.transform(allFailures);
        ExecutionResult<Void> finishResult = buildLifecycleController.finishBuild(reportableFailure);
        return exceptionAnalyser.transform(ExecutionResult.maybeFailed(reportableFailure).withFailures(finishResult).getFailures());
    }
}