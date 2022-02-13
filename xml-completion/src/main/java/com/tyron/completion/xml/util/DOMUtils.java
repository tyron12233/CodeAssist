package com.tyron.completion.xml.util;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMElement;

import java.util.List;

public class DOMUtils {

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

    public static String getPrefix(DOMAttr attr) {
        String name = attr.getName();
        if (!name.contains(":")) {
            return name;
        }
        return name.substring(0, name.indexOf(':'));
    }
}
