package com.tyron.completion.xml.repository.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.completion.xml.repository.api.AttrResourceValue;
import com.tyron.completion.xml.repository.api.AttrResourceValueImpl;
import com.tyron.completion.xml.repository.api.AttributeFormat;
import com.tyron.completion.xml.repository.api.ResourceNamespace;
import com.tyron.completion.xml.repository.api.ResourceReference;
import com.tyron.completion.xml.repository.api.ResourceValue;
import com.tyron.completion.xml.repository.api.ResourceValueImpl;
import com.tyron.completion.xml.repository.api.StyleItemResourceValue;
import com.tyron.completion.xml.repository.api.StyleItemResourceValueImpl;
import com.tyron.completion.xml.repository.api.StyleResourceValueImpl;
import com.tyron.completion.xml.repository.api.StyleableResourceValue;
import com.tyron.completion.xml.repository.api.StyleableResourceValueImpl;

import org.eclipse.lemminx.dom.DOMComment;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.dom.DOMProcessingInstruction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ValuesXmlParser implements ResourceParser {

    @Override
    public List<ResourceValue> parse(@NonNull File file,
                                     @NonNull String contents,
                                     @NonNull ResourceNamespace namespace,
                                     @Nullable String name) throws IOException {
        DOMDocument document = DOMParser.getInstance()
                .parse(contents, "", null);
        List<DOMNode> roots = document.getRoots();
        for (DOMNode root : roots) {
            if (root instanceof DOMProcessingInstruction) {
                continue;
            }

            if (SdkConstants.TAG_RESOURCES.equals(root.getNodeName())) {
                return parseResourceTag(root, namespace, name);
            }
        }
        return Collections.emptyList();
    }

    private List<ResourceValue> parseResourceTag(DOMNode root,
                                                 ResourceNamespace namespace,
                                                 String name) {
        List<DOMNode> children = root.getChildren();
        if (children == null) {
            return Collections.emptyList();
        }

        List<ResourceValue> resourceValues = new ArrayList<>();

        for (DOMNode child : children) {
            ResourceType type = ResourceType.fromXmlTag(child);
            if (type == null) {
                continue;
            }

            ResourceValue value = parseType(type, child, namespace, name);

            if (value != null) {
                resourceValues.add(value);
            }
        }

        return resourceValues;
    }

    private ResourceValue parseType(ResourceType resourceType,
                                    DOMNode child,
                                    ResourceNamespace namespace,
                                    String name) {
        switch (resourceType) {
            case COLOR:
                return parseColor(child, namespace, name);
            case STRING:
                return parseString(child, namespace, name);
            case BOOL:
                return parseBoolean(child, namespace, name);
            case INTEGER:
                return parseInteger(child, namespace, name);
            case STYLE:
                return parseStyle(child, namespace, name);
            case STYLEABLE:
                return parseStyleable(child, namespace, name);
            case ATTR:
                return parseAttrResourceValue(child, namespace, name);
            case PUBLIC:
                return parsePublic(child, namespace, name);
            case ID:
                return parseId(child, namespace, name);
            default:
                return null;
        }
    }

    @Nullable
    private ResourceValue parseId(DOMNode child, ResourceNamespace namespace, String libraryName) {
        String name = child.getAttribute("name");
        if (name == null) {
            return null;
        }

        ResourceReference resourceReference =
                new ResourceReference(namespace, ResourceType.ID, name);
        return new ResourceValueImpl(resourceReference, null, libraryName);
    }

    @Nullable
    private ResourceValue parsePublic(DOMNode child,
                                      ResourceNamespace namespace,
                                      String libraryName) {
        String type = child.getAttribute("type");
        if (type == null) {
            return null;
        }
        ResourceType resourceType = ResourceType.fromXmlTagName(type);
        if (resourceType == null) {
            return null;
        }
        String name = child.getAttribute("name");
        if (name == null) {
            return null;
        }
        ResourceReference resourceReference =
                new ResourceReference(namespace, ResourceType.PUBLIC, name);
        return new ResourceValueImpl(resourceReference, null, libraryName);
    }

    @Nullable
    private ResourceValue parseColor(DOMNode child,
                                     ResourceNamespace namespace,
                                     String libraryName) {
        String name = child.getAttribute("name");
        if (name == null) {
            return null;
        }

        DOMNode firstChild = child.getFirstChild();
        if (firstChild == null) {
            return null;
        }
        if (!firstChild.isText()) {
            return null;
        }

        String value = firstChild.getTextContent();
        ResourceReference reference = new ResourceReference(namespace, ResourceType.COLOR, name);
        return new ResourceValueImpl(reference, value, libraryName);
    }

    @Nullable
    private ResourceValue parseString(DOMNode node,
                                      ResourceNamespace namespace,
                                      String libraryName) {
        String name = node.getAttribute("name");
        if (name == null) {
            return null;
        }

        DOMNode firstChild = node.getFirstChild();
        if (firstChild == null) {
            return null;
        }
        if (!firstChild.isText()) {
            return null;
        }

        String value = firstChild.getTextContent();
        ResourceReference reference = new ResourceReference(namespace, ResourceType.STRING, name);
        return new ResourceValueImpl(reference, value, libraryName);
    }

    @Nullable
    private ResourceValue parseBoolean(DOMNode node,
                                       ResourceNamespace namespace,
                                       String libraryName) {
        String name = node.getAttribute("name");
        if (name == null) {
            return null;
        }

        DOMNode firstChild = node.getFirstChild();
        if (firstChild == null) {
            return null;
        }
        if (!firstChild.isText()) {
            return null;
        }

        String value = firstChild.getTextContent();
        ResourceReference reference = new ResourceReference(namespace, ResourceType.BOOL, name);
        return new ResourceValueImpl(reference, value, libraryName);
    }

    @Nullable
    private ResourceValue parseInteger(DOMNode node,
                                       ResourceNamespace namespace,
                                       String libraryName) {
        String name = node.getAttribute("name");
        if (name == null) {
            return null;
        }

        DOMNode firstChild = node.getFirstChild();
        if (firstChild == null) {
            return null;
        }
        if (!firstChild.isText()) {
            return null;
        }

        String value = firstChild.getTextContent();
        ResourceReference reference = new ResourceReference(namespace, ResourceType.INTEGER, name);
        return new ResourceValueImpl(reference, value, libraryName);
    }

    @Nullable
    private ResourceValue parseStyle(DOMNode node,
                                     ResourceNamespace namespace,
                                     String libraryName) {
        String name = node.getAttribute("name");
        if (name == null) {
            return null;
        }

        String parent = node.getAttribute("parent");
        StyleResourceValueImpl styleResource =
                new StyleResourceValueImpl(namespace, name, parent, libraryName);

        List<DOMNode> children = node.getChildren();
        if (children == null) {
            return styleResource;
        }

        for (DOMNode child : children) {
            String nodeName = child.getNodeName();
            if (!SdkConstants.TAG_ITEM.equals(nodeName)) {
                continue;
            }

            StyleItemResourceValue item = parseStyleItem(child, namespace, libraryName);
            if (item != null) {
                styleResource.addItem(item);
            }
        }

        return styleResource;
    }

    @Nullable
    private StyleItemResourceValue parseStyleItem(DOMNode node,
                                                  ResourceNamespace namespace,
                                                  String libraryName) {
        String attributeName = node.getAttribute("name");
        if (attributeName == null) {
            return null;
        }

        DOMNode firstChild = node.getFirstChild();
        if (firstChild == null || !firstChild.isText()) {
            return null;
        }

        String value = firstChild.getTextContent();
        return new StyleItemResourceValueImpl(namespace, attributeName, value, libraryName);
    }

    @Nullable
    private StyleableResourceValue parseStyleable(DOMNode node,
                                                  ResourceNamespace namespace,
                                                  String libraryName) {
        String name = node.getAttribute("name");
        if (name == null) {
            return null;
        }

        if ("ViewGroup_Layout".equals(name)) {
            System.out.println(name);
        }

        StyleableResourceValueImpl resourceValue =
                new StyleableResourceValueImpl(namespace, name, null, null);

        List<DOMNode> children = node.getChildren();
        if (children == null) {
            return resourceValue;
        }

        for (DOMNode child : children) {
            ResourceType type = ResourceType.fromXmlTag(child);

            if (ResourceType.ATTR.equals(type)) {
                AttrResourceValue attr = parseAttrResourceValue(child, namespace, libraryName);
                if (attr != null) {
                    resourceValue.addValue(attr);
                }
            }
        }

        return resourceValue;
    }

    @Nullable
    private AttrResourceValue parseAttrResourceValue(DOMNode node,
                                                     ResourceNamespace namespace,
                                                     String libraryName) {
        String name = node.getAttribute("name");
        if (name == null) {
            return null;
        }
        AttrResourceValueImpl resourceValue =
                new AttrResourceValueImpl(namespace, name, libraryName);

        String format = node.getAttribute("format");
        if (format != null) {
            Set<AttributeFormat> parse = AttributeFormat.parse(format);
            resourceValue.setFormats(parse);
        }

        List<DOMNode> children = node.getChildren();
        boolean hasEnum = false;
        boolean hasFlag = false;
        if (children != null) {
            for (DOMNode child : children) {
                String nodeName = child.getNodeName();
                if (nodeName == null) {
                    continue;
                }

                if (SdkConstants.TAG_FLAG.equals(nodeName)) {
                    hasFlag = true;
                } else if (SdkConstants.TAG_ENUM.equals(nodeName)) {
                    hasEnum = true;
                } else {
                    hasFlag = false;
                    hasEnum = false;
                    continue;
                }

                String attributeName = child.getAttribute("name");
                if (attributeName == null) {
                    continue;
                }
                String value = child.getAttribute("value");
                if (value == null) {
                    continue;
                }
                Integer integer = Ints.tryParse(value);
                String description = null;
                DOMNode previous = child.getPreviousSibling();
                if (previous != null && previous.isComment()) {
                    DOMComment comment = (DOMComment) previous;
                    description = comment.getTextContent();
                }
                resourceValue.addValue(attributeName, integer, description);
            }
        }

        if (hasEnum &&
            !resourceValue.getFormats()
                    .contains(AttributeFormat.ENUM)) {
            ImmutableSet<AttributeFormat> build =
                    ImmutableSet.<AttributeFormat>builder().addAll(resourceValue.getFormats())
                            .add(AttributeFormat.ENUM)
                            .build();
            resourceValue.setFormats(build);
        } else if (hasFlag &&
                   !resourceValue.getFormats()
                           .contains(AttributeFormat.FLAGS)) {
            ImmutableSet<AttributeFormat> build =
                    ImmutableSet.<AttributeFormat>builder().addAll(resourceValue.getFormats())
                            .add(AttributeFormat.FLAGS)
                            .build();
            resourceValue.setFormats(build);
        }
        return resourceValue;
    }
}
