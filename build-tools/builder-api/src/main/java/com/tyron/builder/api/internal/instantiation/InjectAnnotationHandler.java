package com.tyron.builder.api.internal.instantiation;

import java.lang.annotation.Annotation;

/**
 * Responsible for defining the behaviour of a particular annotation used for injection.
 *
 * <p>Implementations must be registered as global scoped services.</p>
 */
public interface InjectAnnotationHandler {
    Class<? extends Annotation> getAnnotationType();
}
