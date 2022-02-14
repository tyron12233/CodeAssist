package com.tyron.completion.xml.util;

import static com.tyron.completion.xml.util.AttributeProcessingUtil.*;
import static com.tyron.completion.xml.util.AttributeProcessingUtil.getLayoutStyleablePrimary;
import static com.tyron.completion.xml.util.AttributeProcessingUtil.getLayoutStyleableSecondary;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableSet;
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
import java.util.function.Function;

public class AndroidAttributeUtils {

    public static void addManifestAttributes(@NonNull CompletionList.Builder builder,
                                             @NonNull ResourceRepository repository,
                                             @NonNull DOMNode node,
                                             @NonNull ResourceNamespace namespace) {
        Function<String, Set<String>> provider = tag -> {
            String manifestStyleName = AndroidXmlTagUtils.getManifestStyleName(tag);
            if (manifestStyleName != null) {
                return ImmutableSet.of(manifestStyleName);
            }
            return ImmutableSet.of(tag);
        };
        List<AttrResourceValue> tagAttributes =
                getTagAttributes(repository, node, namespace, provider, ImmutableSet::of);
        addAttributes(tagAttributes, builder);
    }

    public static void addLayoutAttributes(@NonNull CompletionList.Builder builder,
                                           @NonNull ResourceRepository repository,
                                           @NonNull DOMNode node,
                                           @NonNull ResourceNamespace namespace) {
        List<AttrResourceValue> tagAttributes =
                getTagAttributes(repository, node, namespace, ImmutableSet::of);

        DOMNode parentNode = node.getParentNode();
        if (parentNode != null) {
            Function<String, Set<String>> provider =
                    tag -> ImmutableSet.of(tag, getLayoutStyleablePrimary(tag),
                                           getLayoutStyleableSecondary(tag));
            tagAttributes.addAll(getTagAttributes(repository, parentNode, namespace, provider));
        }

        addAttributes(tagAttributes, builder);
    }

    private static void addAttributes(List<AttrResourceValue> tagAttributes, CompletionList.Builder builder) {
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
