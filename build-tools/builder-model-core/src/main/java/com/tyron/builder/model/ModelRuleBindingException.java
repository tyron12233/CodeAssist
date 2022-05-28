package com.tyron.builder.model;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.Incubating;

/**
 * Thrown when there is a problem binding the model element references of a model rule.
 * <p>
 * Should always be thrown as the cause of a {@link com.tyron.builder.model.InvalidModelRuleException}.
 */
@Incubating
public class ModelRuleBindingException extends BuildException {

    public ModelRuleBindingException(String message) {
        super(message);
    }

}
