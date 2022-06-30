package com.tyron.builder.api.internal.attributes;

import com.tyron.builder.api.attributes.Attribute;

import java.util.Map;
import java.util.Set;

public interface AttributeDescriber {
    Set<Attribute<?>> getAttributes();
    String describeAttributeSet(Map<Attribute<?>, ?> attributes);
    String describeMissingAttribute(Attribute<?> attribute, Object consumerValue);
    String describeExtraAttribute(Attribute<?> attribute, Object producerValue);
}
