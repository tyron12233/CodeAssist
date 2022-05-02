package com.tyron.builder.internal.metaobject;

import com.tyron.builder.api.internal.DynamicObjectAware;

public abstract class DynamicObjectUtil {
    public static DynamicObject asDynamicObject(Object object) {
        if (object instanceof DynamicObject) {
            return (DynamicObject)object;
        } else if (object instanceof DynamicObjectAware) {
            return ((DynamicObjectAware) object).getAsDynamicObject();
        } else {
            return new BeanDynamicObject(object);
        }
    }
}
