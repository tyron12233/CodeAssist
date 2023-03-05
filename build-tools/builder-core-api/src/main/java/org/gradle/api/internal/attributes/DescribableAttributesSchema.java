package org.gradle.api.internal.attributes;

import org.gradle.api.attributes.AttributesSchema;

import java.util.List;

public interface DescribableAttributesSchema extends AttributesSchema {

    List<AttributeDescriber> getConsumerDescribers();

    void addConsumerDescriber(AttributeDescriber describer);

}
