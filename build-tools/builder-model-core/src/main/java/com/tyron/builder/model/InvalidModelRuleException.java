package com.tyron.builder.model;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;

/**
 * Thrown when there is a problem with the usage of a model rule.
 * <p>
 * This exception is different to {@link InvalidModelRuleDeclarationException} in that it signifies a problem
 * with using a model rule in a particular context, whereas {@code InvalidModelRuleDeclarationException} signifies
 * a problem with the declaration of the model rule itself (which therefore means that the rule could not be used in any context).
 * <p>
 * This exception should always have cause, that provides information about the actual problem.
 */
@Incubating
@Contextual
public class InvalidModelRuleException extends BuildException {

    // The usage pattern of this exception providing the rule identity and the cause providing the detail is the
    // way it is due to how we render chained exceptions on build failures.
    // That is, because the information is usually dense, splitting things up this way provides better output.

    private final String descriptor;

    public InvalidModelRuleException(ModelRuleDescriptor descriptor, Throwable cause) {
        super("There is a problem with model rule " + descriptor.toString() + ".", cause);
        if (cause == null) {
            throw new IllegalArgumentException("'cause' cannot be null");
        }
        this.descriptor = descriptor.toString();
    }

    public String getDescriptor() {
        return descriptor;
    }
}
