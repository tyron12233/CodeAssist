package com.tyron.builder.internal.jvm;

import com.tyron.builder.api.jvm.ModularitySpec;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.Property;

import javax.inject.Inject;

public class DefaultModularitySpec implements ModularitySpec {

    private final Property<Boolean> inferModulePath;

    @Inject
    public DefaultModularitySpec(ObjectFactory objects) {
        inferModulePath = objects.property(Boolean.class).convention(true);
    }

    @Override
    public Property<Boolean> getInferModulePath() {
        return inferModulePath;
    }
}
