package org.gradle.model;

import org.gradle.api.Incubating;
import org.gradle.model.internal.core.ModelPath;
import org.gradle.model.internal.core.rule.describe.ModelRuleDescriptor;
import org.gradle.model.internal.type.ModelType;

/**
 * Thrown when at attempt is made to mutate a subject of a rule after the rule has completed.
 * <p>
 * This can potentially happen when a reference to the subject is retained during a rule and then used afterwards,
 * Such as when an anonymous inner class or closure “closes over” the subject.
 */
@Incubating
public class ModelViewClosedException extends ReadOnlyModelViewException {
    public ModelViewClosedException(ModelPath path, ModelType<?> type, ModelRuleDescriptor ruleDescriptor) {
        super(createMessage("closed", path, type, ruleDescriptor));
    }
}
