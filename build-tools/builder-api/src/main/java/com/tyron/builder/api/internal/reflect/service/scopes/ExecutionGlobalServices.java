package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;

public class ExecutionGlobalServices {

    PropertyWalker createPropertyWalker() {
        return new PropertyWalker() {
            @Override
            public void visitProperties(Object instance,
                                        TypeValidationContext validationContext,
                                        PropertyVisitor visitor) {

            }
        };
    }
}
