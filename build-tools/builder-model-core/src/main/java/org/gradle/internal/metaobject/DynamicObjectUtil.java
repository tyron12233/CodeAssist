package org.gradle.internal.metaobject;

import org.gradle.api.internal.DynamicObjectAware;

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
