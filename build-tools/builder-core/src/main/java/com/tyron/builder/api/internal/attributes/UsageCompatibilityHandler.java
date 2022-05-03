package com.tyron.builder.api.internal.attributes;

import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.attributes.LibraryElements;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.snapshot.impl.CoercingStringValueSnapshot;

class UsageCompatibilityHandler {
    private final IsolatableFactory isolatableFactory;
    private final NamedObjectInstantiator instantiator;

    UsageCompatibilityHandler(IsolatableFactory isolatableFactory, NamedObjectInstantiator instantiator) {
        this.isolatableFactory = isolatableFactory;
        this.instantiator = instantiator;
    }

    public <T> ImmutableAttributes doConcat(DefaultImmutableAttributesFactory factory, ImmutableAttributes node, Attribute<T> key, Isolatable<T> value) {
        assert key.getName().equals(Usage.USAGE_ATTRIBUTE.getName()) : "Should only be invoked for 'com.tyron.builder.usage', got '" + key.getName() + "'";
        // Replace deprecated usage values
        String val;
        boolean typedUsage = false;
        if (value instanceof CoercingStringValueSnapshot) {
            val = ((CoercingStringValueSnapshot) value).getValue();
        } else {
            typedUsage = true;
            val = value.isolate().toString();
        }
        // TODO Add a deprecation warning in Gradle 6.0
        if (val.endsWith("-jars")) {
            return doConcatWithReplacement(factory, node, key, typedUsage, val.replace("-jars", ""), LibraryElements.JAR);
        } else if (val.endsWith("-classes")) {
            return doConcatWithReplacement(factory, node, key, typedUsage, val.replace("-classes", ""), LibraryElements.CLASSES);
        } else if (val.endsWith("-resources")) {
            return doConcatWithReplacement(factory, node, key, typedUsage, val.replace("-resources", ""), LibraryElements.RESOURCES);
        } else {
            return factory.doConcatIsolatable(node, key, value);
        }

    }

    private <T> ImmutableAttributes doConcatWithReplacement(DefaultImmutableAttributesFactory factory, ImmutableAttributes node, Attribute<T> key, boolean typedUsage, String usage, String libraryElements) {
        if (typedUsage) {
            ImmutableAttributes usageNode = factory.doConcatIsolatable(node, key, isolatableFactory.isolate(instantiator.named(Usage.class, usage)));
            return factory.doConcatIsolatable(usageNode, LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, isolatableFactory.isolate(instantiator.named(LibraryElements.class, libraryElements)));
        } else {
            ImmutableAttributes usageNode = factory.doConcatIsolatable(node, key, new CoercingStringValueSnapshot(usage, instantiator));
            return factory.doConcatIsolatable(usageNode, Attribute.of(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE.getName(), String.class), new CoercingStringValueSnapshot(libraryElements, instantiator));
        }
    }
}
