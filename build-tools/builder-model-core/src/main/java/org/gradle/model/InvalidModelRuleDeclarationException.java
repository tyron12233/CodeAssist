package org.gradle.model;

import org.gradle.api.GradleException;
import org.gradle.api.Incubating;
import org.gradle.internal.exceptions.Contextual;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;

/**
 * Thrown when a model rule, or source of model rules, is declared in an invalid way.
 */
@Incubating
@Contextual
public class InvalidModelRuleDeclarationException extends GradleException {

    public InvalidModelRuleDeclarationException(String message) {
        super(message);
    }

    public InvalidModelRuleDeclarationException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidModelRuleDeclarationException(ModelRuleDescriptor descriptor, Throwable cause) {
        super("Declaration of model rule " + descriptor.toString() + " is invalid.", cause);
        if (cause == null) {
            throw new IllegalArgumentException("'cause' cannot be null");
        }
    }

    public InvalidModelRuleDeclarationException(ModelRuleDescriptor descriptor, String message) {
        super(String.format("%s is not a valid model rule method: %s", descriptor, message));
    }
}
