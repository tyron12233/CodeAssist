package com.tyron.builder.common.resources;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;
import com.android.SdkConstants;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Utility class to handle Nodes.
 *
 * - convert Node from one XML {@link Document} to be used by another Document
 * - compare Nodes and attributes.
 */
class NodeUtils {

    /**
     * Makes a new document adopt a node from a different document, and correctly reassign namespace
     * and prefix
     * @param document the new document
     * @param node the node to adopt.
     * @return the adopted node.
     */
    static Node adoptNode(Document document, Node node) {
        Node newNode = document.adoptNode(node);

        updateNamespace(newNode, document);

        return newNode;
    }

    /**
     * Duplicates a node then makes the new document adopt the duplicated node and correctly reassign
     * namespace and prefix.
     * @param document the new document
     * @param node the node to duplicate then adopt.
     * @return the new node
     */
    static Node duplicateAndAdoptNode(Document document, Node node) {
        Node newNode = duplicateNode(document, node);
        updateNamespace(newNode, document);
        return newNode;
    }

    /**
     * Duplicates a node. Does not adjust namespaces and prefixes.
     * @param document the new document
     * @param node the node to duplicate
     * @return the new node
     */
    static Node duplicateNode(Document document, Node node) {
        Node newNode;
        if (node.getNamespaceURI() != null) {
            newNode = document.createElementNS(node.getNamespaceURI(), node.getNodeName());
        } else {
            newNode = document.createElement(node.getNodeName());
        }

        // copy the attributes
        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0 ; i < attributes.getLength(); i++) {
            Attr attr = (Attr) attributes.item(i);

            Attr newAttr;
            if (attr.getNamespaceURI() != null) {
                newAttr = document.createAttributeNS(attr.getNamespaceURI(), attr.getNodeName());
                newNode.getAttributes().setNamedItemNS(newAttr);
            } else {
                newAttr = document.createAttribute(attr.getName());
                newNode.getAttributes().setNamedItem(newAttr);
            }

            newAttr.setValue(attr.getValue());
        }

        // then duplicate the sub-nodes.
        NodeList children = node.getChildNodes();
        for (int i = 0 ; i < children.getLength() ; i++) {
            Node child = children.item(i);
            Node duplicatedChild;
            switch (child.getNodeType()) {
                case Node.ELEMENT_NODE:
                    duplicatedChild = duplicateNode(document, child);
                    break;
                case Node.CDATA_SECTION_NODE:
                    duplicatedChild = document.createCDATASection(child.getNodeValue());
                    break;
                case Node.TEXT_NODE:
                    duplicatedChild = document.createTextNode(child.getNodeValue());
                    break;
                default: continue;
            }
            newNode.appendChild(duplicatedChild);
        }

        return newNode;
    }

    static void addAttribute(Document document, Node node,
                             String namespaceUri, String attrName, String attrValue) {
        Attr attr;
        if (namespaceUri != null) {
            attr = document.createAttributeNS(namespaceUri, attrName);
        } else {
            attr = document.createAttribute(attrName);
        }

        attr.setValue(attrValue);

        if (namespaceUri != null) {
            node.getAttributes().setNamedItemNS(attr);
        } else {
            node.getAttributes().setNamedItem(attr);
        }
    }

    /**
     * Updates the namespace of a given node (and its children) to work in a given document
     * @param node the node to update
     * @param document the new document
     */
    private static void updateNamespace(Node node, Document document) {

        // first process this node
        processSingleNodeNamespace(node, document);

        // then its attributes
        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null) {
            for (int i = 0, n = attributes.getLength(); i < n; i++) {
                Node attribute = attributes.item(i);
                if (!processSingleNodeNamespace(attribute, document)) {
                    String nsUri = attribute.getNamespaceURI();
                    if (nsUri != null) {
                        attributes.removeNamedItemNS(nsUri, attribute.getLocalName());
                    } else {
                        attributes.removeNamedItem(attribute.getLocalName());
                    }
                    // When removing a node, the next item will be at the same index.
                    i--;
                    n--;
                }
            }
        }

        // then do it for the children nodes.
        NodeList children = node.getChildNodes();
        if (children != null) {
            for (int i = 0, n = children.getLength(); i < n; i++) {
                Node child = children.item(i);
                if (child != null) {
                    updateNamespace(child, document);
                }
            }
        }
    }

    /**
     * Update the namespace of a given node to work with a given document.
     *
     * @param node the node to update
     * @param document the new document
     *
     * @return false if the attribute is to be dropped
     */
    private static boolean processSingleNodeNamespace(Node node, Document document) {
        if (SdkConstants.XMLNS.equals(node.getLocalName())) {
            return false;
        }

        String ns = node.getNamespaceURI();
        if (ns != null) {
            // XMLNS prefix declarations will be moved to the top-level docAttributes so remove any local declarations.
            // The scoping of prefixes will thus be lost, but any prefix overriding in the original document should
            // have been respected when cloning.
            if (ns.equals(SdkConstants.XMLNS_URI)) {
                return false;
            }
            NamedNodeMap docAttributes = getDocumentNamespaceAttributes(document);

            String prefix = getPrefixForNs(docAttributes, ns);
            if (prefix == null) {
                prefix = getUniqueNsAttribute(docAttributes);
                Attr nsAttr = document.createAttribute(prefix);
                nsAttr.setValue(ns);
                docAttributes.setNamedItem(nsAttr);
            }

            // set the prefix on the node, by removing the xmlns: start
            prefix = prefix.substring(6);
            node.setPrefix(prefix);
        }

        return true;
    }

    /**
     * Gets the attribute map where xmlns:prefix=uri attributes will be stored by updateNamespace.
     */
    @VisibleForTesting
    @NotNull
    static NamedNodeMap getDocumentNamespaceAttributes(Document document) {
        NamedNodeMap attributes = document.getChildNodes().item(0).getAttributes();
        assert attributes != null;
        return attributes;
    }

    /**
     * Looks for an existing prefix for a given namespace.
     * The prefix must start with "xmlns:". The whole prefix is returned.
     * @param attributes the attributes to look through
     * @param namespaceURI the namespace to find.
     * @return the found prefix or null if none is found.
     */
    @VisibleForTesting
    static String getPrefixForNs(@NotNull NamedNodeMap attributes, String namespaceURI) {
        for (int i = 0, n = attributes.getLength(); i < n; i++) {
            Attr attribute = (Attr)attributes.item(i);
            if (namespaceURI.equals(attribute.getValue()) && attribute.getName().startsWith(SdkConstants.XMLNS_PREFIX)) {
                return attribute.getName();
            }
        }

        return null;
    }

    private static String getUniqueNsAttribute(@NotNull NamedNodeMap attributes) {
        int i = 1;
        while (true) {
            String name = String.format("xmlns:ns%d", i++);
            if (attributes.getNamedItem(name) == null) {
                return name;
            }
        }
    }

    static boolean compareElementNode(@NotNull Node node1, @NotNull Node node2, boolean strict) {
        // See if either element has a namespace. If so, compare by namespace and local name
        // so that the prefix in the nodeName is irrelevant. Otherwise, compare the nodeName.
        if (node1.getNamespaceURI() != null || node2.getNamespaceURI() != null) {
            if (!Objects.equal(node1.getLocalName(), node2.getLocalName()) ||
                !Objects.equal(node1.getNamespaceURI(), node2.getNamespaceURI())) {
                return false;
            }
        } else if (!node1.getNodeName().equals(node2.getNodeName()) ){
            return false;
        }

        NamedNodeMap attr1 = node1.getAttributes();
        NamedNodeMap attr2 = node2.getAttributes();

        if (!compareAttributes(attr1, attr2)) {
            return false;
        }

        if (strict) {
            return compareChildren(node1.getChildNodes(), node2.getChildNodes());
        }

        return compareContent(node1.getChildNodes(), node2.getChildNodes());
    }

    private static boolean compareChildren(
            @NotNull NodeList children1,
            @NotNull NodeList children2) {
        // because this represents a resource values, we're going to be very strict about this
        // comparison.
        if (children1.getLength() != children2.getLength()) {
            return false;
        }

        for (int i = 0, n = children1.getLength(); i < n; i++) {
            Node child1 = children1.item(i);
            Node child2 = children2.item(i);

            short nodeType = child1.getNodeType();
            if (nodeType != child2.getNodeType()) {
                return false;
            }

            switch (nodeType) {
                case Node.ELEMENT_NODE:
                    if (!compareElementNode(child1, child2, true)) {
                        return false;
                    }
                    break;
                case Node.CDATA_SECTION_NODE:
                case Node.TEXT_NODE:
                case Node.COMMENT_NODE:
                    if (!child1.getNodeValue().equals(child2.getNodeValue())) {
                        return false;
                    }
                    break;
            }
        }

        return true;
    }

    private static boolean compareContent(
            @NotNull NodeList children1,
            @NotNull NodeList children2) {
        // only compares the content (ie not the text node).

        // accumulate both true children list.
        List<Node> childList = getElementChildren(children1);
        List<Node> childList2 = getElementChildren(children2);

        if (childList.size() != childList2.size()) {
            return false;
        }

        // no attempt to match nodes one to one.
        for (Node child : childList) {
            boolean found = false;
            for (Node child2 : childList2) {
                if (compareElementNode(child, child2, false)) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                return false;
            }
        }

        return true;
    }

    @NotNull
    private static List<Node> getElementChildren(@NotNull NodeList children) {
        List<Node> results = Lists.newArrayListWithExpectedSize(children.getLength());

        final int len = children.getLength();
        for (int i = 0; i < len; i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                results.add(child);
            }
        }

        return results;
    }

    @VisibleForTesting
    static boolean compareAttributes(
            @NotNull NamedNodeMap attrMap1,
            @NotNull NamedNodeMap attrMap2) {
        if (attrMap1.getLength() != attrMap2.getLength()) {
            return false;
        }

        for (int i = 0, n = attrMap1.getLength(); i < n; i++) {
            Attr attr1 = (Attr) attrMap1.item(i);

            String ns1 = attr1.getNamespaceURI();

            Attr attr2;
            if (ns1 != null) {
                attr2 = (Attr) attrMap2.getNamedItemNS(ns1, attr1.getLocalName());
            }  else {
                attr2 = (Attr) attrMap2.getNamedItem(attr1.getName());
            }

            if (attr2 == null || !attr2.getValue().equals(attr1.getValue())) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    static String getAttribute(@NotNull Node node, @NotNull String attrName) {
        Attr attr = (Attr) node.getAttributes().getNamedItem(attrName);
        if (attr != null) {
            return attr.getValue();
        }
        return null;
    }
}