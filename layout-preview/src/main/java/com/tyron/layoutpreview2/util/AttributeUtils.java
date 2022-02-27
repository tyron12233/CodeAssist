package com.tyron.layoutpreview2.util;

import androidx.annotation.NonNull;

import com.tyron.completion.xml.repository.Repository;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceUrl;
import com.tyron.completion.xml.repository.api.ResourceValue;

public class AttributeUtils {

    /**
     * Recursively resolve this reference value
     *
     * If the value is already resolved, it is returned.
     * @param value The value defined from an xml file
     * @param contextNamespace The namespace of the current xml file
     * @param resolver The resolver for the current namespace
     * @param repository The repository of the current module
     * @return The resolved string
     */
    public static String resolveString(String value,
                                       @NonNull ResourceNamespace contextNamespace,
                                       @NonNull ResourceNamespace.Resolver resolver,
                                       @NonNull Repository repository) {
        final ResourceUrl parse = ResourceUrl.parse(value);
        if (parse == null) {
            return value;
        }

        final ResourceReference resolvedReference = parse.resolve(contextNamespace, resolver);
        if (resolvedReference == null) {
            return value;
        }

        final ResourceValue resolvedValue = repository.getValue(resolvedReference);
        if (resolvedValue == null) {
            return value;
        }

        return resolveString(resolvedValue.getValue(), contextNamespace, resolver, repository);
    }
}
