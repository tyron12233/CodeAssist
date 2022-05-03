package com.tyron.builder.internal.metaobject;

import java.util.HashMap;
import java.util.Map;

/**
 * Presents a {@link DynamicObject} view of multiple objects at once.
 *
 * Can be used to provide a dynamic view of an object with enhancements.
 */
public abstract class CompositeDynamicObject extends AbstractDynamicObject {

    private static final DynamicObject[] NONE = new DynamicObject[0];

    private DynamicObject[] objects = NONE;
    private DynamicObject[] updateObjects = NONE;

    protected void setObjects(DynamicObject... objects) {
        this.objects = objects;
        updateObjects = objects;
    }

    protected void setObjectsForUpdate(DynamicObject... objects) {
        this.updateObjects = objects;
    }

    @Override
    public boolean hasProperty(String name) {
        for (DynamicObject object : objects) {
            if (object.hasProperty(name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DynamicInvokeResult tryGetProperty(String name) {
        for (DynamicObject object : objects) {
            DynamicInvokeResult result = object.tryGetProperty(name);
            if (result.isFound()) {
                return result;
            }
        }
        return DynamicInvokeResult.notFound();
    }

    @Override
    public DynamicInvokeResult trySetProperty(String name, Object value) {
        for (DynamicObject object : updateObjects) {
            DynamicInvokeResult result = object.trySetProperty(name, value);
            if (result.isFound()) {
                return result;
            }
        }
        return DynamicInvokeResult.notFound();
    }

    @Override
    public Map<String, Object> getProperties() {
        Map<String, Object> properties = new HashMap<String, Object>();
        for (int i = objects.length - 1; i >= 0; i--) {
            DynamicObject object = objects[i];
            properties.putAll(object.getProperties());
        }
        properties.put("properties", properties);
        return properties;
    }

    @Override
    public boolean hasMethod(String name, Object... arguments) {
        for (DynamicObject object : objects) {
            if (object.hasMethod(name, arguments)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
        for (DynamicObject object : objects) {
            DynamicInvokeResult result = object.tryInvokeMethod(name, arguments);
            if (result.isFound()) {
                return result;
            }
        }
        return DynamicInvokeResult.notFound();
    }
}
