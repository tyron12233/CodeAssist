package com.tyron.builder.api.internal.tasks.properties;

public class DefaultFinalizingValidatingProperty extends AbstractValidatingProperty {
    private final PropertyValue value;
    private LifecycleAwareValue lifecycleAware;

    public DefaultFinalizingValidatingProperty(String propertyName, PropertyValue value, boolean optional, ValidationAction validationAction) {
        super(propertyName, value, optional, validationAction);
        this.value = value;
    }

    @Override
    public void prepareValue() {
        super.prepareValue();
        Object obj = value.call();
        // TODO - move this to PropertyValue instead
        if (obj instanceof LifecycleAwareValue) {
            lifecycleAware = (LifecycleAwareValue) obj;
            lifecycleAware.prepareValue();
        }
    }

    @Override
    public void cleanupValue() {
        if (lifecycleAware != null) {
            lifecycleAware.cleanupValue();
        }
    }
}