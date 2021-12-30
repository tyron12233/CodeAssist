package com.tyron.layoutpreview.convert;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.Array;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;

import org.openjdk.javax.xml.parsers.DocumentBuilder;
import org.openjdk.javax.xml.parsers.DocumentBuilderFactory;
import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.openjdk.javax.xml.transform.Transformer;
import org.openjdk.javax.xml.transform.TransformerConfigurationException;
import org.openjdk.javax.xml.transform.TransformerException;
import org.openjdk.javax.xml.transform.TransformerFactory;
import org.openjdk.javax.xml.transform.dom.DOMSource;
import org.openjdk.javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.StringWriter;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a {@link Layout} object into its xml representation
 */
public class LayoutToXmlConverter {

    private final ProteusContext mContext;
    private Document mDocument;

    public LayoutToXmlConverter(ProteusContext context) {
        mContext = context;
        int mChildrenAttributeId = Objects.requireNonNull(mContext.getParser(ViewGroup.class.getName()))
                .getAttributeId("children");
    }

    public String convert(@NonNull Layout layout) throws ParserConfigurationException,
            TransformerException {
        mDocument = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .newDocument();
        Element element = mDocument.createElement(layout.type);
        mDocument.appendChild(element);
        addAttributes(element, null, layout);
        addExtraAttributes(element, null, layout);

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        DOMSource source = new DOMSource(mDocument);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        transformer.transform(source, result);
        return writer.toString();
    }

    private void addAttributes(Element element, Layout parent, Layout layout) {
        if (layout.attributes == null) {
            return;
        }

        for (Layout.Attribute attribute : layout.attributes) {
            if (isChildrenAttribute(layout, attribute)) {
                if (attribute.value.isArray()) {
                    Array array = attribute.value.getAsArray();
                    for (int i = 0; i < array.size(); i++) {
                        Value child = array.get(i);
                        if (!child.isLayout()) {
                            continue;
                        }
                        addChildren(element, layout, child.getAsLayout());
                    }
                }
                continue;
            }
            String key = ProteusHelper.getAttributeName(mContext, parent, layout.type, attribute.id);
            element.setAttribute(key, attribute.value.toString());
        }
    }

    private void addExtraAttributes(Element element, Layout parent, Layout layout) {
        if (layout.extras == null) {
            return;
        }

        for (Map.Entry<String, Value> entry : layout.extras.entrySet()) {
           addAttribute(element, parent, entry.getKey(), entry.getValue());
        }
    }

    private void addAttribute(Element element, Layout parent, String key, Value value) {
        if (value.isPrimitive()) {
            element.setAttribute(key, value.toString());
            return;
        }
        if (key.equals("children") && value.isArray()) {
            Array array = value.getAsArray();
            for (int i = 0; i < array.size(); i++) {
                Value child = array.get(i);
                if (child.isLayout()) {
                    addChildren(element, parent, child.getAsLayout());
                }
            }
        }
    }

    private void addChildren(Element element, Layout parent, Layout child) {
        Element newElement = mDocument.createElement(child.type);
        addAttributes(newElement, parent, child);
        addExtraAttributes(newElement, parent, child);
        element.appendChild(newElement);
    }

    private boolean isChildrenAttribute(Layout layout, Layout.Attribute attribute) {
        ViewTypeParser<View> parser = mContext.getParser(layout.type);
        if (parser == null) {
            return false;
        }
        ViewTypeParser.AttributeSet.Attribute children = parser.getAttributeSet().getAttribute("children");
        if (children == null) {
            return false;
        }
        return children.id == attribute.id;
    }
}
