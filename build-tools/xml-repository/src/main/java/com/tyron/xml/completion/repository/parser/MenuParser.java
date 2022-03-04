package com.tyron.xml.completion.repository.parser;

import com.tyron.builder.compiler.manifest.SdkConstants;
import com.tyron.builder.compiler.manifest.resources.ResourceType;
import com.tyron.xml.completion.repository.ResourceItem;
import com.tyron.xml.completion.repository.api.ResourceNamespace;
import com.tyron.xml.completion.repository.api.ResourceReference;
import com.tyron.xml.completion.repository.api.ResourceValue;
import com.tyron.xml.completion.repository.api.ResourceValueImpl;
import com.tyron.xml.completion.util.DOMUtils;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kotlin.io.FilesKt;

public class MenuParser implements ResourceParser {
    @Override
    public List<ResourceValue> parse(@NotNull File file,
                                     @Nullable String contents,
                                     @NotNull ResourceNamespace namespace,
                                     @Nullable String libraryName) throws IOException {
        if (contents == null) {
            return Collections.emptyList();
        }

        DOMDocument parsed = DOMParser.getInstance().parse(contents, file.toURI().toString(), null);
        if (parsed == null) {
            return Collections.emptyList();
        }

        DOMElement rootElement = DOMUtils.getRootElement(parsed);
        if (rootElement == null) {
            return Collections.emptyList();
        }

        if (!SdkConstants.TAG_MENU.equals(rootElement.getTagName())) {
            return Collections.emptyList();
        }

        return parseMenu(file, rootElement, namespace, libraryName);
    }

    private List<ResourceValue> parseMenu(File file,
                                          DOMElement root,
                                          ResourceNamespace namespace,
                                          String libraryName) {
        String name = FilesKt.getNameWithoutExtension(file);
        ResourceReference resourceReference = new ResourceReference(namespace, ResourceType.MENU, name);
        ResourceValueImpl resourceValue = new ResourceValueImpl(resourceReference, null, libraryName);

        List<ResourceValue> resourceValues = new ArrayList<>();
        resourceValues.add(resourceValue);

        ResourceNamespace.Resolver resolver =
                DOMUtils.getNamespaceResolver(root.getOwnerDocument());
        String prefix = resolver.uriToPrefix(ResourceNamespace.ANDROID.getXmlNamespaceUri());
        if (prefix != null) {
            List<DOMElement> items = DOMUtils.findElementsWithTagName(root, "item");
            for (DOMElement item : items) {
                DOMAttr id = item.getAttributeNode(prefix, "id");
                if (id != null) {
                    ResourceValue idValue = parseIdValue(id.getValue(), namespace, libraryName);
                    if (idValue != null) {
                        resourceValues.add(idValue);
                    }
                }
            }
        }
        return resourceValues;
    }

    private ResourceValue parseIdValue(String value,
                                       ResourceNamespace namespace,
                                       String libraryName) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith(SdkConstants.NEW_ID_PREFIX)) {
            return null;
        }
        String name = value.substring(SdkConstants.NEW_ID_PREFIX.length());
        if (name.isEmpty()) {
            return null;
        }
        try {
            ResourceReference reference = new ResourceReference(namespace, ResourceType.ID, name);
            return new ResourceValueImpl(reference, null, libraryName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
