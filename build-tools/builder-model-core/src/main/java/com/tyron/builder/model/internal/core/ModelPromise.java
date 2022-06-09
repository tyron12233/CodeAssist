package com.tyron.builder.model.internal.core;

import com.tyron.builder.model.internal.type.ModelType;

public interface ModelPromise {

    <T> boolean canBeViewedAs(ModelType<T> type);

    // These methods return strings rather than types because it may be more complicated than what is able to be expressed via a ModelType.
    // Also, we don't want to encourage compatibility checking occurring by looping through such types as we have more options for optimising the compatibility check internally.
    // Also also, these methods are only called for reporting so values should typically not be precomputed.
    Iterable<String> getTypeDescriptions(MutableModelNode node);
}
