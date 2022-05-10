package com.tyron.builder.api.attributes;

import com.tyron.builder.api.Action;

/**
 * Represents something that carries attributes by utilizing an
 * {@link AttributeContainer} that is configurable.
 *
 * @param <SELF> type extending this interface
 *
 * @since 3.4
 */
public interface HasConfigurableAttributes<SELF> extends HasAttributes {

    /**
     * Configure the attribute container that provides the attributes
     * associated with this domain object.
     */
    SELF attributes(Action<? super AttributeContainer> action);
}
