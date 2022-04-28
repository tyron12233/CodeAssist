package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.constants.ConstantsAnalysisResult;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessingResult;
import com.tyron.builder.workers.internal.DefaultWorkResult;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ApiCompilerResult extends DefaultWorkResult {

    private final AnnotationProcessingResult annotationProcessingResult = new AnnotationProcessingResult();
    private final ConstantsAnalysisResult constantsAnalysisResult = new ConstantsAnalysisResult();
    private final Map<String, Set<String>> sourceToClassMapping = new HashMap<>();

    public ApiCompilerResult() {
        super(true, null);
    }

    public AnnotationProcessingResult getAnnotationProcessingResult() {
        return annotationProcessingResult;
    }

    public ConstantsAnalysisResult getConstantsAnalysisResult() {
        return constantsAnalysisResult;
    }

    public Map<String, Set<String>> getSourceClassesMapping() {
        return sourceToClassMapping;
    }
}

