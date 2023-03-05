package com.tyron.builder.gradle.internal.pipeline;

import com.tyron.builder.api.transform.TransformException;
import com.tyron.builder.api.transform.TransformInput;
import com.android.ide.common.util.ReferenceHolder;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.COMPILED_CLASSES, secondaryTaskCategories = {TaskCategory.SOURCE_PROCESSING})
public abstract class NonIncrementalTransformTask extends TransformTask {
    @TaskAction
    void transform() throws IOException, TransformException, InterruptedException {

        final ReferenceHolder<List<TransformInput>> consumedInputs = ReferenceHolder.empty();
        final ReferenceHolder<List<TransformInput>> referencedInputs = ReferenceHolder.empty();

//        GradleTransformExecution preExecutionInfo =
//                GradleTransformExecution.newBuilder()
//                        .setType(
//                                AnalyticsUtil.getTransformType(getTransform().getClass())
//                                        .getNumber())
//                        .setIsIncremental(false)
//                        .setTransformClassName(getTransform().getClass().getName())
//                        .build();
//
//        AnalyticsService analyticsService = getAnalyticsService().get();
//        analyticsService.recordBlock(
//                GradleBuildProfileSpan.ExecutionType.TASK_TRANSFORM_PREPARATION,
//                preExecutionInfo,
//                getProjectPath().get(),
//                getVariantName(),
//                new Recorder.VoidBlock() {
//                    @Override
//                    public void call() {
//                        consumedInputs.setValue(computeNonIncTransformInput(consumedInputStreams));
//                        referencedInputs.setValue(
//                                computeNonIncTransformInput(referencedInputStreams));
//                    }
//                });

        consumedInputs.setValue(computeNonIncTransformInput(consumedInputStreams));
        referencedInputs.setValue(
                computeNonIncTransformInput(referencedInputStreams));

        runTransform(
                consumedInputs.getValue(),
                referencedInputs.getValue(),
                /*isIncremental=*/ false,
                ImmutableList.of()
//                preExecutionInfo,
//                analyticsService
        );
    }
}