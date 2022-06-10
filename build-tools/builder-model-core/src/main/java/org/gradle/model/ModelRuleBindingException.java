package org.gradle.model;

import org.gradle.api.BuildException;
import org.gradle.api.Incubating;

/**
 * Thrown when there is a problem binding the model element references of a model rule.
 * <p>
 * Should always be thrown as the cause of a {@link org.gradle.model.InvalidModelRuleException}.
 */
@Incubating
public class ModelRuleBindingException extends BuildException {

    public ModelRuleBindingException(String message) {
        super(message);
    }

}
