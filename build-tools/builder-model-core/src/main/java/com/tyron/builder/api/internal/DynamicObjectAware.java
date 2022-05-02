package com.tyron.builder.api.internal;

import com.tyron.builder.internal.metaobject.DynamicObject;

/**
 * An object that can present a dynamic view of itself.
 *
 * The exposed dynamic object <i>may</i> provide functionality over and above what the type implementing
 * this interface can do. For example, the {@link DynamicObject} may provide the ability to register new
 * properties or implement methods that this object does not provide in a concrete way.
 */
public interface DynamicObjectAware {

    /**
     * Returns a {@link DynamicObject} for this object. This should include all static and dynamic properties and methods for this object.
     *
     * @return The dynamic object.
     */
    DynamicObject getAsDynamicObject();
}
