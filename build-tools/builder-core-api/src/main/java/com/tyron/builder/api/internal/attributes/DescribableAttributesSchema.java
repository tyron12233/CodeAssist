package com.tyron.builder.api.internal.attributes;

import com.tyron.builder.api.attributes.AttributesSchema;

import java.util.List;

public interface DescribableAttributesSchema extends AttributesSchema {

    List<AttributeDescriber> getConsumerDescribers();

    void addConsumerDescriber(AttributeDescriber describer);

}
