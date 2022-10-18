package org.gradle.internal.reflect.validation;

import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.plugin.use.PluginId;

public class DefaultTypeValidationProblemBuilder extends AbstractValidationProblemBuilder<TypeProblemBuilder> implements TypeProblemBuilder {
    private Class<?> type;

    public DefaultTypeValidationProblemBuilder(DocumentationRegistry documentationRegistry, PluginId pluginId) {
        super(documentationRegistry, pluginId);
    }

    @Override
    public TypeProblemBuilder forType(Class<?> type) {
        this.type = type;
        return this;
    }

    public TypeValidationProblem build() {
        if (problemId == null) {
            throw new IllegalStateException("You must set the problem id");
        }
        if (type == null) {
            throw new IllegalStateException("The type on which the problem should be reported hasn't been set");
        }
        if (shortProblemDescription == null) {
            throw new IllegalStateException("You must provide at least a short description of the problem");
        }
        return new TypeValidationProblem(
                problemId,
                severity,
                typeIrrelevantInErrorMessage ? TypeValidationProblemLocation.irrelevant() :  TypeValidationProblemLocation.inType(type, pluginId),
                shortProblemDescription,
                longDescription,
                reason,
                cacheabilityProblemOnly,
                userManualReference,
                possibleSolutions
        );
    }
}