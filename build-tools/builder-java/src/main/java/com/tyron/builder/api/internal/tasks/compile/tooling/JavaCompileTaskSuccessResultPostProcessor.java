package com.tyron.builder.api.internal.tasks.compile.tooling;

import com.tyron.builder.api.internal.tasks.compile.CompileJavaBuildOperationType;
import com.tyron.builder.api.internal.tasks.compile.CompileJavaBuildOperationType.Result.AnnotationProcessorDetails;
import com.tyron.builder.api.internal.tasks.execution.ExecuteTaskBuildOperationType;
import com.tyron.builder.internal.build.event.OperationResultPostProcessor;
import com.tyron.builder.internal.build.event.types.AbstractTaskResult;
import com.tyron.builder.internal.build.event.types.DefaultAnnotationProcessorResult;
import com.tyron.builder.internal.build.event.types.DefaultJavaCompileTaskSuccessResult;
import com.tyron.builder.internal.build.event.types.DefaultTaskSuccessResult;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.OperationFinishEvent;
import com.tyron.builder.internal.operations.OperationIdentifier;
import com.tyron.builder.internal.operations.OperationStartEvent;
import com.tyron.builder.tooling.internal.protocol.events.InternalJavaCompileTaskOperationResult;
import com.tyron.builder.tooling.internal.protocol.events.InternalJavaCompileTaskOperationResult.InternalAnnotationProcessorResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JavaCompileTaskSuccessResultPostProcessor implements OperationResultPostProcessor {

    private static final Object TASK_MARKER = new Object();
    private final Map<OperationIdentifier, CompileJavaBuildOperationType.Result> results =
            new ConcurrentHashMap<>();
    private final Map<OperationIdentifier, Object> parentsOfOperationsWithJavaCompileTaskAncestor =
            new ConcurrentHashMap<>();

    @Override
    public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        if (buildOperation.getDetails() instanceof ExecuteTaskBuildOperationType.Details) {
            parentsOfOperationsWithJavaCompileTaskAncestor.put(buildOperation.getId(), TASK_MARKER);
        } else if (buildOperation.getParentId() != null &&
                   parentsOfOperationsWithJavaCompileTaskAncestor
                           .containsKey(buildOperation.getParentId())) {
            parentsOfOperationsWithJavaCompileTaskAncestor
                    .put(buildOperation.getId(), buildOperation.getParentId());
        }
    }

    @Override
    public void finished(BuildOperationDescriptor buildOperation,
                         OperationFinishEvent finishEvent) {
        if (finishEvent.getResult() instanceof CompileJavaBuildOperationType.Result) {
            CompileJavaBuildOperationType.Result result =
                    (CompileJavaBuildOperationType.Result) finishEvent.getResult();
            OperationIdentifier taskBuildOperationId =
                    findTaskOperationId(buildOperation.getParentId());
            results.put(taskBuildOperationId, result);
        }
        parentsOfOperationsWithJavaCompileTaskAncestor.remove(buildOperation.getId());
    }

    private OperationIdentifier findTaskOperationId(OperationIdentifier id) {
        Object parent = parentsOfOperationsWithJavaCompileTaskAncestor.get(id);
        if (parent == TASK_MARKER) {
            return id;
        }
        return findTaskOperationId((OperationIdentifier) parent);
    }

    @Override
    public AbstractTaskResult process(AbstractTaskResult taskResult,
                                      OperationIdentifier taskBuildOperationId) {
        CompileJavaBuildOperationType.Result compileResult = results.remove(taskBuildOperationId);
        if (taskResult instanceof DefaultTaskSuccessResult && compileResult != null) {
            return new DefaultJavaCompileTaskSuccessResult((DefaultTaskSuccessResult) taskResult,
                    toAnnotationProcessorResults(compileResult.getAnnotationProcessorDetails()));
        }
        return taskResult;
    }

    private List<InternalAnnotationProcessorResult> toAnnotationProcessorResults(List<AnnotationProcessorDetails> allDetails) {
        if (allDetails == null) {
            return null;
        }
        List<InternalAnnotationProcessorResult> results =
                new ArrayList<InternalAnnotationProcessorResult>(allDetails.size());
        for (AnnotationProcessorDetails details : allDetails) {
            results.add(toAnnotationProcessorResult(details));
        }
        return results;
    }

    private InternalAnnotationProcessorResult toAnnotationProcessorResult(AnnotationProcessorDetails details) {
        return new DefaultAnnotationProcessorResult(details.getClassName(),
                toAnnotationProcessorType(details.getType()),
                Duration.ofMillis(details.getExecutionTimeInMillis()));
    }

    private String toAnnotationProcessorType(AnnotationProcessorDetails.Type type) {
        switch (type) {
            case AGGREGATING:
                return InternalAnnotationProcessorResult.TYPE_AGGREGATING;
            case ISOLATING:
                return InternalAnnotationProcessorResult.TYPE_ISOLATING;
            case UNKNOWN:
                return InternalAnnotationProcessorResult.TYPE_UNKNOWN;
        }
        throw new IllegalArgumentException("Missing conversion for enum constant " + type);
    }
}
