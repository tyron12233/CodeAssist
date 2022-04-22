package com.tyron.builder.api.internal.tasks.compile.processing;

import static com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType.AGGREGATING;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileManager;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import com.tyron.builder.api.internal.tasks.compile.incremental.compilerapi.deps.GeneratedResource;
import com.tyron.builder.api.internal.tasks.compile.incremental.processing.AnnotationProcessorResult;

/**
 * The strategy used for aggregating annotation processors.
 * @see AggregatingProcessor
 */
class AggregatingProcessingStrategy extends IncrementalProcessingStrategy {

    AggregatingProcessingStrategy(AnnotationProcessorResult result) {
        super(result);
        result.setType(AGGREGATING);
    }

    @Override
    public void recordProcessingInputs(Set<String> supportedAnnotationTypes, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        validateAnnotations(annotations);
        recordAggregatedTypes(supportedAnnotationTypes, annotations, roundEnv);
    }

    private void validateAnnotations(Set<? extends TypeElement> annotations) {
        for (TypeElement annotation : annotations) {
            Retention retention = annotation.getAnnotation(Retention.class);
            if (retention != null && retention.value() == RetentionPolicy.SOURCE) {
                result.setFullRebuildCause("'@" + annotation.getSimpleName() + "' has source retention. Aggregating annotation processors require class or runtime retention");
            }
        }
    }

    private void recordAggregatedTypes(Set<String> supportedAnnotationTypes, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (supportedAnnotationTypes.contains("*")) {
            result.getAggregatedTypes().addAll(namesOfElements(roundEnv.getRootElements()));
        } else {
            for (TypeElement annotation : annotations) {
                result.getAggregatedTypes().addAll(namesOfElements(roundEnv.getElementsAnnotatedWith(annotation)));
            }
        }
    }

    private static Set<String> namesOfElements(Set<? extends Element> orig) {
        if (orig == null || orig.isEmpty()) {
            return Collections.emptySet();
        }
        return orig
                .stream()
                .map(ElementUtils::getTopLevelType)
                .map(ElementUtils::getElementName)
                .collect(Collectors.toSet());
    }

    @Override
    public void recordGeneratedType(CharSequence name, Element[] originatingElements) {
        result.getGeneratedAggregatingTypes().add(name.toString());
    }

    @Override
    public void recordGeneratedResource(JavaFileManager.Location location, CharSequence pkg, CharSequence relativeName, Element[] originatingElements) {
        GeneratedResource.Location resourceLocation = GeneratedResource.Location.from(location);
        if (resourceLocation == null) {
            result.setFullRebuildCause(location + " is not supported for incremental annotation processing");
        } else {
            result.getGeneratedAggregatingResources().add(new GeneratedResource(resourceLocation, pkg, relativeName));
        }
    }

    @Override
    public String toString() {
        return "Aggregating strategy for " + result.getClassName();
    }
}
