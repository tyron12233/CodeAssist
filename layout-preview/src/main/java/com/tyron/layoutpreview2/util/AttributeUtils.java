package com.tyron.layoutpreview2.util;

import android.view.View;

import androidx.annotation.NonNull;

import com.tyron.xml.completion.repository.Repository;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceUrl;
import com.tyron.xml.completion.repository.api.ResourceValue;
import com.tyron.xml.completion.util.DOMUtils;
import com.tyron.layoutpreview2.EditorContext;

import org.eclipse.lemminx.dom.DOMAttr;

public class AttributeUtils {

    public static String resolve(View view, DOMAttr attr) {
        EditorContext context = EditorContext.getEditorContext(view.getContext());
        final ResourceNamespace documentNs = DOMUtils.getNamespace(attr.getOwnerDocument());
        if (documentNs == null) {
            return attr.getValue();
        }
        final ResourceNamespace.Resolver resolver =
                DOMUtils.getNamespaceResolver(attr.getOwnerDocument());
        return resolve(attr.getValue(), documentNs, resolver, context.getRepository());
    }

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
    public static String resolve(String value,
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

        return resolve(resolvedValue.getValue(), contextNamespace, resolver, repository);
    }
}
