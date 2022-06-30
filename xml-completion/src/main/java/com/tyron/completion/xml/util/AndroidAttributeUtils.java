package com.tyron.completion.xml.util;

import static com.tyron.completion.xml.util.AttributeProcessingUtil.*;
import static com.tyron.completion.xml.util.AttributeProcessingUtil.getLayoutStyleablePrimary;
import static com.tyron.completion.xml.util.AttributeProcessingUtil.getLayoutStyleableSecondary;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableSet;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.xml.insert.AttributeInsertHandler;
import com.tyron.xml.completion.repository.ResourceRepository;
import com.tyron.xml.completion.repository.api.AttrResourceValue;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.util.DOMUtils;

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
        addAttributes(tagAttributes, node, builder);
    }

    public static void addLayoutAttributes(@NonNull CompletionList.Builder builder,
                                           @NonNull ResourceRepository repository,
                                           @NonNull DOMNode node,
                                           @NonNull ResourceNamespace namespace) {
        DOMDocument ownerDocument = node.getOwnerDocument();
        DOMElement rootElement = DOMUtils.getRootElement(ownerDocument);
        if (node.equals(rootElement)) {
            ResourceNamespace.Resolver resolver = DOMUtils.getNamespaceResolver(ownerDocument);
            if (resolver != null) {
                addNamespaceAttributes(builder, rootElement, resolver);
            }
        }
        List<AttrResourceValue> tagAttributes =
                getTagAttributes(repository, node, namespace, ImmutableSet::of);

        DOMNode parentNode = node.getParentNode();
        if (parentNode != null) {
            Function<String, Set<String>> provider =
                    tag -> ImmutableSet.of(tag, getLayoutStyleablePrimary(tag),
                                           getLayoutStyleableSecondary(tag));
            tagAttributes.addAll(getTagAttributes(repository, parentNode, namespace, provider));
        }

        addAttributes(tagAttributes, node, builder);
    }

    private static void addNamespaceAttributes(CompletionList.Builder builder,
                                               DOMElement rootElement,
                                               ResourceNamespace.Resolver resolver) {
        if (resolver.uriToPrefix(ResourceNamespace.ANDROID.getXmlNamespaceUri()) == null) {
            CompletionItem completionItem =
                    CompletionItem.create("androidNs", "Namespace", "xmlns:android",
                                          DrawableKind.Attribute);
            completionItem.addFilterText("android");
            completionItem.setInsertHandler(
                    new AttributeInsertHandler(ResourceNamespace.ANDROID.getXmlNamespaceUri(),
                                               completionItem));
            builder.addItem(completionItem);
        }

        if (resolver.uriToPrefix(ResourceNamespace.RES_AUTO.getXmlNamespaceUri()) == null) {
            CompletionItem completionItem = CompletionItem.create("appNs", "Namespace", "xmlns:app",
                                                                  DrawableKind.Attribute);
            completionItem.addFilterText("app");
            completionItem.setInsertHandler(
                    new AttributeInsertHandler(ResourceNamespace.RES_AUTO.getXmlNamespaceUri(),
                                               completionItem));
            builder.addItem(completionItem);
        }
    }

    private static void addAttributes(List<AttrResourceValue> tagAttributes,
                                      DOMNode node,
                                      CompletionList.Builder builder) {
        ResourceNamespace.Resolver resolver =
                DOMUtils.getNamespaceResolver(node.getOwnerDocument());

        Set<String> uniques = new HashSet<>();
        for (AttrResourceValue tagAttribute : tagAttributes) {
            String name = tagAttribute.getName();
            ResourceReference reference;
            if (name.contains(":")) {
                String prefix = name.substring(0, name.indexOf(':'));
                String fixedName = name.substring(name.indexOf(':') + 1);
                ResourceNamespace namespace =
                        ResourceNamespace.fromNamespacePrefix(prefix, tagAttribute.getNamespace(),
                                                              tagAttribute.getNamespaceResolver());
                reference =
                        new ResourceReference(namespace, tagAttribute.getResourceType(), fixedName);
            } else {
                reference = tagAttribute.asReference();
            }
            String prefix = resolver.uriToPrefix(reference.getNamespace()
                                                         .getXmlNamespaceUri());
            if (TextUtils.isEmpty(prefix)) {
                if (tagAttribute.getLibraryName() != null) {
                    // default to res-auto namespace, commonly prefixed as 'app'
                    prefix = resolver.uriToPrefix(ResourceNamespace.RES_AUTO.getXmlNamespaceUri());
                }
            }
            String commitText = TextUtils.isEmpty(prefix) ? reference.getName() : prefix +
                                                                                  ":" +
                                                                                  reference.getName();
            if (uniques.contains(commitText)) {
                continue;
            }

            CompletionItem attribute = CompletionItem.create(commitText, "Attribute", commitText,
                                                             DrawableKind.Attribute);
            attribute.addFilterText(commitText);
            attribute.addFilterText(reference.getName());
            attribute.setInsertHandler(new AttributeInsertHandler(attribute));
            builder.addItem(attribute);
            uniques.add(commitText);
        }
    }
}