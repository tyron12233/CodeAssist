package org.gradle.api.internal.tasks.properties;

import groovy.lang.GString;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.util.internal.DeferredUtil;

import javax.annotation.Nullable;

public class InputParameterUtils {
    @Nullable
    public static Object prepareInputParameterValue(InputPropertySpec inputProperty, Task task) {
        String propertyName = inputProperty.getPropertyName();
        try {
            return prepareInputParameterValue(inputProperty.getValue());
        } catch (Exception ex) {
            throw new PropertyEvaluationException(task, propertyName, ex);
        }
    }

    @Nullable
    public static Object prepareInputParameterValue(@Nullable Object value) {
        Object unpacked = DeferredUtil.unpackOrNull(value);
        return finalizeValue(unpacked);
    }

    @Nullable
    private static Object finalizeValue(@Nullable Object unpacked) {
        if (unpacked instanceof GString) {
            return unpacked.toString();
        }
        if (unpacked instanceof FileCollection) {
            return ((FileCollection) unpacked).getFiles();
        }
        return unpacked;
    }
}