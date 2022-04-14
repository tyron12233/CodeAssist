package com.tyron.builder.api.internal.tasks.compile.processing;

import static com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType.ISOLATING;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.GeneratedResource;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import java.util.Set;

/**
 * The strategy for isolating annotation processors.
 *
 * @see IsolatingProcessor
 */
class IsolatingProcessingStrategy extends IncrementalProcessingStrategy {

    IsolatingProcessingStrategy(AnnotationProcessorResult result) {
        super(result);
        result.setType(ISOLATING);
    }

    @Override
    public void recordProcessingInputs(Set<String> supportedAnnotationTypes, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    }

    @Override
    public void recordGeneratedType(CharSequence name, Element[] originatingElements) {
        String generatedType = name.toString();
        Set<String> originatingTypes = ElementUtils.getTopLevelTypeNames(originatingElements);
        int size = originatingTypes.size();
        if (size != 1) {
            result.setFullRebuildCause("the generated type '" + generatedType + "' must have exactly one originating element, but had " + size);
        }
        result.addGeneratedType(generatedType, originatingTypes);
    }

    @Override
    public void recordGeneratedResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element[] originatingElements) {
        GeneratedResource.Location resourceLocation = GeneratedResource.Location.from(location);
        if (resourceLocation == null) {
            result.setFullRebuildCause(location + " is not supported for incremental annotation processing");
            return;
        }
        GeneratedResource generatedResource = new GeneratedResource(resourceLocation, pkg, relativeName);

        Set<String> originatingTypes = ElementUtils.getTopLevelTypeNames(originatingElements);
        int size = originatingTypes.size();
        if (size != 1) {
            result.setFullRebuildCause("the generated resource '" + generatedResource + "' must have exactly one originating element, but had " + size);
        }
        result.addGeneratedResource(generatedResource, originatingTypes);
    }
}
