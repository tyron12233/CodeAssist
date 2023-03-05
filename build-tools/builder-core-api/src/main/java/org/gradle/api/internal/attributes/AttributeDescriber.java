package org.gradle.api.internal.attributes;

import org.gradle.api.attributes.Attribute;

import java.util.Map;
import java.util.Set;

public interface AttributeDescriber {
    Set<Attribute<?>> getAttributes();
    String describeAttributeSet(Map<Attribute<?>, ?> attributes);
    String describeMissingAttribute(Attribute<?> attribute, Object consumerValue);
    String describeExtraAttribute(Attribute<?> attribute, Object producerValue);
}
