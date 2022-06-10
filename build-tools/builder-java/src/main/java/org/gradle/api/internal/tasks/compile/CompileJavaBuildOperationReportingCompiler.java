package org.gradle.api.internal.tasks.compile;

import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.api.internal.tasks.compile.CompileJavaBuildOperationType.Result.AnnotationProcessorDetails;
import org.gradle.api.internal.tasks.compile.incremental.processing.AnnotationProcessingResult;
import org.gradle.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;
import org.gradle.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType;
import org.gradle.api.tasks.WorkResult;
import org.gradle.language.base.internal.compile.Compiler;


import java.util.ArrayList;
import java.util.List;

public class CompileJavaBuildOperationReportingCompiler implements Compiler<JavaCompileSpec> {

    private static final CompileJavaBuildOperationType.Details DETAILS = new CompileJavaBuildOperationType.Details() {
    };

    private final TaskInternal task;
    private final Compiler<JavaCompileSpec> delegate;
    private final BuildOperationExecutor buildOperationExecutor;

    public CompileJavaBuildOperationReportingCompiler(TaskInternal task, Compiler<JavaCompileSpec> delegate, BuildOperationExecutor buildOperationExecutor) {
        this.task = task;
        this.delegate = delegate;
        this.buildOperationExecutor = buildOperationExecutor;
    }

    @Override
    public WorkResult execute(final JavaCompileSpec spec) {
        return buildOperationExecutor.call(new CallableBuildOperation<WorkResult>() {
            @Override
            public BuildOperationDescriptor.Builder description() {
                return BuildOperationDescriptor.displayName("Compile Java for " + task.getIdentityPath()).details(DETAILS);
            }

            @Override
            public WorkResult call(BuildOperationContext context) {
                WorkResult result = delegate.execute(spec);
                context.setResult(toBuildOperationResult(result));
                return result;
            }

            private Result toBuildOperationResult(WorkResult result) {
                if (result instanceof ApiCompilerResult) {
                    AnnotationProcessingResult annotationProcessingResult = ((ApiCompilerResult) result).getAnnotationProcessingResult();
                    List<AnnotationProcessorDetails> details = new ArrayList<AnnotationProcessorDetails>();
                    for (AnnotationProcessorResult processorResult : annotationProcessingResult.getAnnotationProcessorResults()) {
                        details.add(toAnnotationProcessorDetails(processorResult));
                    }
                    return new Result(details);
                }
                return new Result(null);
            }

            private DefaultAnnotationProcessorDetails toAnnotationProcessorDetails(AnnotationProcessorResult result) {
                return new DefaultAnnotationProcessorDetails(result.getClassName(), toType(result.getType()), result.getExecutionTimeInMillis());
            }

            private AnnotationProcessorDetails.Type toType(IncrementalAnnotationProcessorType type) {
                if (type == IncrementalAnnotationProcessorType.AGGREGATING) {
                    return AnnotationProcessorDetails.Type.AGGREGATING;
                }
                if (type == IncrementalAnnotationProcessorType.ISOLATING) {
                    return AnnotationProcessorDetails.Type.ISOLATING;
                }
                return AnnotationProcessorDetails.Type.UNKNOWN;
            }
        });
    }

    private static class Result implements CompileJavaBuildOperationType.Result {

        private final List<AnnotationProcessorDetails> annotationProcessorDetails;

        Result(List<AnnotationProcessorDetails> annotationProcessorDetails) {
            this.annotationProcessorDetails = annotationProcessorDetails;
        }

        @Override
        public List<AnnotationProcessorDetails> getAnnotationProcessorDetails() {
            return annotationProcessorDetails;
        }

    }

    private static class DefaultAnnotationProcessorDetails implements AnnotationProcessorDetails {

        private final String className;
        private final Type type;
        private final long executionTimeInMillis;

        DefaultAnnotationProcessorDetails(String className, Type type, long executionTimeInMillis) {
            this.className = className;
            this.type = type;
            this.executionTimeInMillis = executionTimeInMillis;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public long getExecutionTimeInMillis() {
            return executionTimeInMillis;
        }

    }

}
