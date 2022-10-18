package com.tyron.xml.completion.util;

import com.tyron.xml.completion.repository.api.ResourceNamespace;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMComment;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMProcessingInstruction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

public class DOMUtils {

    private static final String RESOLVER_KEY = "uriResolver";
    private static final String NAMESPACE_KEY = "namespace";

    private static final WeakHashMap<DOMNode, Map<String, Object>> sUserDataHolder = new WeakHashMap<>();

    public static List<DOMElement> findElementsWithTagName(DOMElement element, String tagName) {
        List<DOMElement> elements = new ArrayList<>();
        List<DOMNode> children = element.getChildren();
        for (DOMNode child : children) {
            if (!(child instanceof DOMElement)) {
                continue;
            }

            if (tagName.equals(((DOMElement) child).getTagName())) {
                elements.add((DOMElement) child);
            }

            elements.addAll(findElementsWithTagName((DOMElement) child, tagName));
        }
        return elements;
    }

    public static String lookupPrefix(DOMAttr attr) {
        return lookupPrefix(attr, getPrefix(attr));
    }

    public static String lookupPrefix(DOMAttr attr, String prefix) {
        DOMElement element = attr.getOwnerElement();
        while (element != null) {
            List<DOMAttr> nodes = element.getAttributeNodes();
            if (nodes != null) {
                for (DOMAttr node : nodes) {
                    if (!node.isXmlns()) {
                        continue;
                    }

                    if (prefix.equals(node.getLocalName())) {
                        return node.getValue();
                    }
                }
            }

            element = element.getParentElement();
        }
        return prefix;
    }

    public static List<DOMElement> getSubTags(DOMElement tag) {
        return tag.getChildren().stream()
                .filter(it -> it instanceof DOMElement)
                .map(it -> (DOMElement) it)
                .collect(Collectors.toList());
    }

    @Nullable
    public static String getNamespace(@NotNull DOMAttr attr) {
        String prefix = getPrefix(attr);
        ResourceNamespace.Resolver namespaceResolver =
                getNamespaceResolver(attr.getOwnerDocument());
        return namespaceResolver.prefixToUri(prefix);
    }

    public static String getPrefix(@NotNull DOMAttr attr) {
        String name = attr.getName();
        if (!name.contains(":")) {
            return name;
        }
        return name.substring(0, name.indexOf(':'));
    }

    @Nullable
    public static DOMElement getRootElement(@NotNull DOMDocument document) {
        List<DOMNode> roots = document.getRoots();
        for (DOMNode root : roots) {
            if (root instanceof DOMElement) {
                return (DOMElement) root;
            }
        }
        return null;
    }

    @NotNull
    public static List<DOMNode> getRootElements(@NotNull DOMDocument document) {
        List<DOMNode> roots = document.getRoots();
        return roots.stream()
                .filter(it -> !(it instanceof DOMProcessingInstruction))
                .collect(Collectors.toList());
    }

    public static ResourceNamespace.Resolver getNamespaceResolver(DOMDocument document) {
        DOMElement rootElement = getRootElement(document);
        if (rootElement == null) {
            return ResourceNamespace.Resolver.EMPTY_RESOLVER;
        }
        Object userData = getUserData(rootElement, RESOLVER_KEY);
        if (userData instanceof ResourceNamespace.Resolver) {
            return (ResourceNamespace.Resolver) userData;
        }

        ResourceNamespace.Resolver resolver = new ResourceNamespace.Resolver() {

            @Nullable
            @Override
            public String uriToPrefix(@NotNull String namespaceUri) {
                return rootElement.getPrefix(namespaceUri);
            }

            @Nullable
            @Override
            public String prefixToUri(@NotNull String namespacePrefix) {
                DOMAttr xmlns = rootElement.getAttributeNode("xmlns", namespacePrefix);
                if (xmlns != null) {
                    return xmlns.getValue();
                }
                return null;
            }
        };
        putUserData(rootElement, RESOLVER_KEY, resolver);
        return resolver;
    }

    public static boolean isClosed(DOMNode nodeAt) {
        if (!nodeAt.isClosed()) {
            return false;
        }
        DOMElement parent = nodeAt.getParentElement();
        if (parent != null && !parent.isClosed()) {
            if (nodeAt.getNodeName().equals(parent.getTagName())) {
                return false;
            }
        }
        return nodeAt.isClosed();
    }

    public static Object getUserData(DOMNode node, String key) {
        final Map<String, Object> map = sUserDataHolder.get(node);
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    public static void putUserData(@NotNull DOMNode node, @NotNull String key, Object value) {
        sUserDataHolder.computeIfAbsent(node, it -> new HashMap<>());
        Map<String, Object> map = sUserDataHolder.get(node);
        if (map != null) {
            map.put(key, value);
        }
    }

    public static void setNamespace(DOMDocument document, ResourceNamespace namespace) {
        putUserData(document, NAMESPACE_KEY, namespace);
    }

    public static List<String> getXmlnsAttributes(DOMDocument document) {
        DOMElement rootElement = getRootElement(document);
        if (rootElement == null) {
            return Collections.emptyList();
        }
        List<DOMAttr> attributeNodes = rootElement.getAttributeNodes();
        if (attributeNodes == null) {
            return Collections.emptyList();
        }

        List<String> namespaces = new ArrayList<>();
        for (DOMAttr attributeNode : attributeNodes) {
            if (attributeNode.isXmlns() || attributeNode.getName().startsWith("xmlns:")) {
                namespaces.add(attributeNode.getValue());
            }
        }
        return namespaces;
    }

    @Nullable
    public static ResourceNamespace getNamespace(DOMDocument document) {
        final Object userData = getUserData(document, NAMESPACE_KEY);
        if (userData instanceof ResourceNamespace) {
            return ((ResourceNamespace) userData);
        }
        return null;
    }

    public static DOMComment findPreviousComment(DOMElement tag) {
        DOMNode current = tag;
        while (current != null && !(current instanceof DOMComment)) {
            current = current.getPreviousSibling();

            if (current == null) {
                break;
            }
        }

        if (current == null) {
            return null;
        }
        return (DOMComment) current;
    }

    public static <T extends DOMNode> List<T> findChildrenOfType(DOMNode element, Class<T> domElementClass) {
        List<T> list = new ArrayList<>();

        LinkedList<DOMNode> queue = new LinkedList<>();
        queue.addFirst(element);

        while (!queue.isEmpty()) {
            DOMNode first = queue.poll();

            if (domElementClass.isAssignableFrom(first.getClass())) {
                //noinspection unchecked
                list.add((T) first);
            }

            queue.addAll(first.getChildren());
        }

        return list;
    }

    public static String getNameWithoutPrefix(DOMAttr it) {
        String name = it.getName();
        if (name.contains(":")) {
            return name.substring(name.indexOf(":") + 1);
        }
        return name;
    }
}
