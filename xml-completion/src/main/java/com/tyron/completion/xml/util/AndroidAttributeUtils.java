package com.tyron.completion.xml.util;

import androidx.annotation.NonNull;

import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.insert.AttributeInsertHandler;
import com.tyron.completion.xml.repository.ResourceRepository;
import com.tyron.completion.xml.repository.api.AttrResourceValue;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AndroidAttributeUtils {

    public static void addLayoutAttributes(@NonNull CompletionList.Builder builder,
                                           @NonNull ResourceRepository repository,
                                           @NonNull DOMNode node,
                                           @NonNull ResourceNamespace namespace) {
        List<AttrResourceValue> tagAttributes =
                AttributeProcessingUtil.getTagAttributes(repository, node, namespace, false);

        DOMNode parentNode = node.getParentNode();
        if (parentNode != null) {
            tagAttributes.addAll(
                    AttributeProcessingUtil.getTagAttributes(repository, parentNode, namespace,
                                                             true));
        }

        Set<String> uniques = new HashSet<>();
        for (AttrResourceValue tagAttribute : tagAttributes) {
            ResourceReference reference = tagAttribute.asReference();
            String commitText = reference.getQualifiedName();
            if (uniques.contains(commitText)) {
                continue;
            }

            CompletionItem attribute =
                    CompletionItem.create(reference.getQualifiedName(), "Attribute",
                                          commitText + "=\"\"", DrawableKind.Attribute);
            attribute.addFilterText(commitText);
            attribute.addFilterText(reference.getName());
            attribute.setInsertHandler(new AttributeInsertHandler(attribute));
            builder.addItem(attribute);
            uniques.add(commitText);
        }
    }
}
