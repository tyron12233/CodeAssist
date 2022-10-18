package org.gradle.internal.extensibility;

import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.internal.metaobject.AbstractDynamicObject;
import org.gradle.internal.metaobject.DynamicInvokeResult;

import java.util.Map;

public class ExtraPropertiesDynamicObjectAdapter extends AbstractDynamicObject {
    private final ExtraPropertiesExtension extension;
    private final Class<?> delegateType;

    public ExtraPropertiesDynamicObjectAdapter(Class<?> delegateType, ExtraPropertiesExtension extension) {
        this.delegateType = delegateType;
        this.extension = extension;
    }

    @Override
    public String getDisplayName() {
        return delegateType.getName();
    }

    @Override
    public boolean hasProperty(String name) {
        return extension.has(name);
    }

    @Override
    public Map<String, ?> getProperties() {
        return extension.getProperties();
    }

    @Override
    public DynamicInvokeResult tryGetProperty(String name) {
        if (extension.has(name)) {
            return DynamicInvokeResult.found(extension.get(name));
        }
        return DynamicInvokeResult.notFound();
    }

    @Override
    public DynamicInvokeResult trySetProperty(String name, Object value) {
        if (extension.has(name)) {
            extension.set(name, value);
            return DynamicInvokeResult.found();
        }
        return DynamicInvokeResult.notFound();
    }
}
