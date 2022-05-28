package com.tyron.builder.model;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.model.internal.core.ModelPath;
import com.tyron.builder.model.internal.core.rule.describe.ModelRuleDescriptor;
import com.tyron.builder.model.internal.type.ModelType;

/**
 * Thrown when an attempt is made to read the value of a model element that is not readable at
 * the time.
 */
@Incubating
public class WriteOnlyModelViewException extends BuildException {

    public WriteOnlyModelViewException(String property,
                                       ModelPath path,
                                       ModelType<?> type,
                                       ModelRuleDescriptor ruleDescriptor) {
        super(createMessage(property, path, type, ruleDescriptor));
    }

    private static String createMessage(String property,
                                        ModelPath path,
                                        ModelType<?> type,
                                        ModelRuleDescriptor ruleDescriptor) {
        StringBuilder result = new StringBuilder();
        result.append("Attempt to read");
        if (property != null) {
            result.append(" property '");
            result.append(property);
            result.append("'");
        }
        result.append(" from a write only view of model element '");
        result.append(path);
        result.append("'");
        if (!type.equals(ModelType.UNTYPED)) {
            result.append(" of type '");
            result.append(type.getDisplayName());
            result.append("'");
        }
        result.append(" given to rule ");
        ruleDescriptor.describeTo(result);
        return result.toString();
    }
}
