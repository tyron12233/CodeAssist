package com.tyron.builder.api.internal.attributes;

import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.attributes.AttributeContainer;

import java.util.Map;

public interface AttributeContainerInternal extends AttributeContainer {

    /**
     * Returns an immutable copy of this attribute set. Implementations are not required to return a distinct instance for each call.
     * Changes to this set are <em>not</em> reflected in the immutable copy.
     *
     * @return an immutable view of this container.
     */
    ImmutableAttributes asImmutable();

    /**
     * Returns a copy of this attribute container as a map. This is an expensive
     * operation which should be limited to cases like diagnostics which are worthy of time.
     * @return a copy of this container, as a map.
     */
    Map<Attribute<?>, ?> asMap();
}
