package org.gradle.api.internal.provider;

import org.gradle.internal.state.ModelObject;
import org.gradle.internal.state.OwnerAware;

public interface PropertyInternal<T> extends ProviderInternal<T>, HasConfigurableValueInternal, OwnerAware {
    /**
     * Sets the property's value from some arbitrary object. Used from the Groovy DSL.
     */
    void setFromAnyValue(Object object);

    /**
     * Associates this property with the task that produces its value.
     */
    void attachProducer(ModelObject owner);
}