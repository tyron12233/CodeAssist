package com.tyron.builder.api.internal.instantiation;


import com.tyron.builder.api.internal.state.ModelObject;

import java.lang.annotation.Annotation;
import java.util.Set;

/**
 * Responsible for defining the behaviour of a particular annotation used for defining the role of a property.
 *
 * <p>Implementations must be registered as global scoped services.</p>
 */
public interface PropertyRoleAnnotationHandler {
    Set<Class<? extends Annotation>> getAnnotationTypes();

    void applyRoleTo(ModelObject owner, Object target);
}
