package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.internal.state.ModelObject;
import com.tyron.builder.api.internal.state.OwnerAware;

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