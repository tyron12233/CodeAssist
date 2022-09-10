package com.android.ide.common.resources;

import static com.android.SdkConstants.*;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.symbols.ResourceValuesXmlParser;
import com.android.resources.ResourceType;
import com.android.utils.PositionXmlParser;
import com.android.utils.XmlUtils;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * This class is deprecated and it's usages will soon move to a new parser in the Symbols package
 * {@link ResourceValuesXmlParser}.
 *
 * <p>Parser for "values" files.
 *
 * <p>This parses the file and returns a list of {@link ResourceMergerItem} object.
 */
@Deprecated
class ValueResourceParser2 {
    @NonNull private final File mFile;
    @Nullable private final String mLibraryName;
    @NonNull private final ResourceNamespace mNamespace;
    private boolean mTrackSourcePositions = true;
    private boolean mCheckDuplicates = true;

    /**
     * Creates the parser for a given file.
     *
     * @param file the file to parse.
     */
    ValueResourceParser2(
            @NonNull File file,
            @NonNull ResourceNamespace namespace,
            @Nullable String libraryName) {
        mFile = file;
        mNamespace = namespace;
        mLibraryName = libraryName;
    }

    /**
     * Sets whether or not to use a source position-tracking XML parser.
     */
    void setTrackSourcePositions(boolean value) {
        mTrackSourcePositions = value;
    }

    /**
     * Tells the parser whether to allow check for duplicate resource items or not.
     */
    void setCheckDuplicates(boolean value) {
        mCheckDuplicates = value;
    }

    /**
     * Parses the file and returns a list of {@link ResourceMergerItem} objects.
     *
     * @return a list of resources.
     * @throws MergingException if a merging exception happens
     */
    @NonNull
    List<ResourceMergerItem> parseFile(DocumentBuilderFactory factory) throws MergingException {
        Document document = parseDocument(mFile, mTrackSourcePositions, factory);

        // get the root node
        Node rootNode = document.getDocumentElement();
        if (rootNode == null) {
            return Collections.emptyList();
        }
        NodeList nodes = rootNode.getChildNodes();

        final int count = nodes.getLength();
        // list containing the result
        List<ResourceMergerItem> resources = new ArrayList<>(count);
        // Multimap to detect duplicates.
        Map<ResourceType, Set<String>> map =
                mCheckDuplicates ? new EnumMap<>(ResourceType.class) : null;

        for (int i = 0, n = nodes.getLength(); i < n; i++) {
            Node node = nodes.item(i);

            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            ResourceMergerItem resource = getResource(node, mFile, mNamespace, mLibraryName);
            if (resource != null) {
                // Check that this is not a duplicate.
                checkDuplicate(resource, map, mFile);

                resources.add(resource);

                if (resource.getType() == ResourceType.STYLEABLE) {
                    // Need to also create ATTR items for its children
                    addStyleableItems(node, resources, map, mFile, mNamespace, mLibraryName);
                }
            }
        }

        return resources;
    }

    /**
     * Returns a new ResourceItem object for a given node.
     *
     * @param node the node representing the resource.
     * @return a ResourceItem object or null.
     */
    @Nullable
    static ResourceMergerItem getResource(
            @NonNull Node node,
            @Nullable File from,
            @NonNull ResourceNamespace namespace,
            @Nullable String libraryName)
            throws MergingException {
        ResourceType type = getType(node, from);
        String name = getName(node);

        if (name != null) {
            if (type != null) {
                ValueResourceNameValidator.validate(name, type, from);
                return new ResourceMergerItem(name, namespace, type, node, null, libraryName);
            }
        } else if (type == ResourceType.PUBLIC) {
            // Allow a <public /> node with no name: this means all resources are private
            return new ResourceMergerItem("", namespace, type, node, null, libraryName);
        }

        return null;
    }

    /**
     * Returns the type of the ResourceItem based on a node's attributes.
     *
     * @param node the node
     * @return the ResourceType or null if it could not be inferred.
     */
    @Nullable
    static ResourceType getType(@NonNull Node node, @Nullable File from) throws MergingException {
        String nodeName = node.getLocalName();

        if (TAG_EAT_COMMENT.equals(nodeName) || TAG_SKIP.equals(nodeName)) {
            return null;
        }

        ResourceType result = ResourceType.fromXmlTag(node);

        if (result != null) {
            return result;
        } else {
            throw MergingException.withMessage(
                            "Can't determine type for tag '%s'", XmlUtils.toXml(node))
                    .withFile(from)
                    .build();
        }
    }

    /**
     * Returns the name of the resource based a node's attributes.
     * @param node the node.
     * @return the name or null if it could not be inferred.
     */
    static String getName(@NonNull Node node) {
        Attr attribute = (Attr) node.getAttributes().getNamedItemNS(null, ATTR_NAME);

        if (attribute != null) {
            return attribute.getValue();
        }

        return null;
    }

    /**
     * Loads the DOM for a given file and returns a {@link Document} object.
     *
     * @param file the file to parse
     * @param trackPositions should track XML node positions
     * @return a Document object.
     * @throws MergingException if a merging exception happens
     */
    @NonNull
    static Document parseDocument(
            @NonNull File file, boolean trackPositions, DocumentBuilderFactory factory)
            throws MergingException {
        try {
            if (trackPositions) {
                return PositionXmlParser.parse(
                        new BufferedInputStream(new FileInputStream(file)), factory);
            }
            else {
                return XmlUtils.parseUtfXmlFile(file, true);
            }
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw MergingException.wrapException(e).withFile(file).build();
        }
    }

    /**
     * Adds any declare styleable attr items below the given declare styleable nodes into the given
     * list.
     *
     * @param styleableNode the declare styleable node
     * @param list the list to add items into
     * @param map map of existing items to detect dups.
     */
    static void addStyleableItems(
            @NonNull Node styleableNode,
            @NonNull List<ResourceMergerItem> list,
            @Nullable Map<ResourceType, Set<String>> map,
            @Nullable File from,
            @NonNull ResourceNamespace namespace,
            @Nullable String libraryName)
            throws MergingException {
        assert styleableNode.getNodeName().equals(TAG_DECLARE_STYLEABLE);
        NodeList nodes = styleableNode.getChildNodes();

        for (int i = 0, n = nodes.getLength(); i < n; i++) {
            Node node = nodes.item(i);

            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            ResourceMergerItem resource = getResource(node, from, namespace, libraryName);
            if (resource != null) {
                assert resource.getType() == ResourceType.ATTR;

                // is the attribute in the android namespace?
                if (!resource.getName().startsWith(ANDROID_NS_NAME_PREFIX)) {
                    if (hasFormatAttribute(node) || XmlUtils.hasElementChildren(node)) {
                        checkDuplicate(resource, map, from);
                        resource.setIgnoredFromDiskMerge(true);
                        list.add(resource);
                    }
                }
            }
        }
    }

    private static void checkDuplicate(
            @NonNull ResourceMergerItem resource,
            @Nullable Map<ResourceType, Set<String>> map,
            @Nullable File from)
            throws MergingException {
        if (map == null) {
            return;
        }

        Set<String> set = map.get(resource.getType());
        if (set == null) {
            set = new HashSet<>();
            map.put(resource.getType(), set);
        }

        String name = resource.getName();
        if (!set.add(name) && resource.getType() != ResourceType.PUBLIC) {
            throw MergingException.withMessage(
                            "Found item %s/%s more than one time",
                            resource.getType().getDisplayName(), name)
                    .withFile(from)
                    .build();
        }
    }

    private static boolean hasFormatAttribute(Node node) {
        return node.getAttributes().getNamedItemNS(null, ATTR_FORMAT) != null;
    }
}
