package org.gradle.internal.jvm;

import org.gradle.api.jvm.ModularitySpec;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

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
