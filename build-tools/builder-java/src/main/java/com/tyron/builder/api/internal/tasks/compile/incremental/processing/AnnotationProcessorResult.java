package com.tyron.builder.api.internal.tasks.compile.incremental.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.GeneratedResource;

import java.io.Serializable;
import java.util.Set;

public class AnnotationProcessorResult implements Serializable {

    private final AnnotationProcessingResult processingResult;
    private final String className;
    private IncrementalAnnotationProcessorType type;
    private long executionTimeInMillis;

    public AnnotationProcessorResult(AnnotationProcessingResult processingResult, String className) {
        this.processingResult = processingResult;
        this.className = className;
    }

    public String getClassName() {
        return className;
    }

    public IncrementalAnnotationProcessorType getType() {
        return type;
    }

    public void setType(IncrementalAnnotationProcessorType type) {
        this.type = type;
    }

    public long getExecutionTimeInMillis() {
        return executionTimeInMillis;
    }

    public void setExecutionTimeInMillis(long executionTimeInMillis) {
        this.executionTimeInMillis = executionTimeInMillis;
    }

    public void addGeneratedType(String name, Set<String> originatingElements) {
        processingResult.addGeneratedType(name, originatingElements);
    }

    public void addGeneratedResource(GeneratedResource resource, Set<String> originatingElements) {
        processingResult.addGeneratedResource(resource, originatingElements);
    }

    public Set<String> getAggregatedTypes() {
        return processingResult.getAggregatedTypes();
    }

    public Set<String> getGeneratedAggregatingTypes() {
        return processingResult.getGeneratedAggregatingTypes();
    }

    public Set<GeneratedResource> getGeneratedAggregatingResources() {
        return processingResult.getGeneratedAggregatingResources();
    }

    public void setFullRebuildCause(String fullRebuildCause) {
        processingResult.setFullRebuildCause(fullRebuildCause);
    }
}

