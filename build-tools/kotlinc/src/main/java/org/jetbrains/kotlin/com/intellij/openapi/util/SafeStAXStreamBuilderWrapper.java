package org.jetbrains.kotlin.com.intellij.openapi.util;

import static org.openjdk.javax.xml.stream.XMLStreamConstants.CHARACTERS;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.COMMENT;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.DTD;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.PROCESSING_INSTRUCTION;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.SPACE;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.START_DOCUMENT;
import static org.openjdk.javax.xml.stream.XMLStreamConstants.START_ELEMENT;

import org.jetbrains.kotlin.org.jdom.AttributeType;
import org.jetbrains.kotlin.org.jdom.Element;
import org.jetbrains.kotlin.org.jdom.Namespace;
import org.openjdk.javax.xml.stream.XMLStreamException;
import org.openjdk.javax.xml.stream.XMLStreamReader;

public class SafeStAXStreamBuilderWrapper {

    public static final SafeJdomFactory FACTORY = new SafeJdomFactory.BaseSafeJdomFactory();

    public static Element build(XMLStreamReader stream,
                                boolean isIgnoreBoundaryWhitespace,
                                boolean isNsSupported,
                                SafeJdomFactory factory) throws XMLStreamException {
        int state = stream.getEventType();
        if (state != START_DOCUMENT) {
            throw new XMLStreamException("beginning");
        }

        Element rootElement = null;
        while (state != END_ELEMENT) {
            switch (state) {
                case START_DOCUMENT:
                case SPACE:
                case CHARACTERS:
                case COMMENT:
                case PROCESSING_INSTRUCTION:
                case DTD:
                    break;

                case START_ELEMENT:
                    rootElement = processElement(stream, isNsSupported, factory);
                    break;

                default:
                    throw new XMLStreamException("Unexpected");
            }

            if (stream.hasNext()) {
                state = stream.next();
            } else {
                throw new XMLStreamException("Unexpected");
            }
        }

        if (rootElement == null) {
            return new Element("empty");
        }
        return rootElement;
    }

    public static Element processElement(XMLStreamReader reader,
                                         boolean isNsSupported,
                                         SafeJdomFactory factory) {
        Element element = factory.element(reader.getLocalName(), isNsSupported
                ? Namespace.getNamespace(reader.getPrefix(), reader.getNamespaceURI())
                : Namespace.NO_NAMESPACE);
        // handle attributes
        for (int i = 0, len = reader.getAttributeCount(); i < len; i++) {
            element.setAttribute(factory.attribute(
                    reader.getAttributeLocalName(i),
                    reader.getAttributeValue(i),
                    AttributeType.valueOf(reader.getAttributeType(i)),
                    isNsSupported ? Namespace.getNamespace(reader.getAttributePrefix(i), reader.getNamespaceURI()) : Namespace.NO_NAMESPACE
            ));
        }

        if (isNsSupported) {
            for (int i = 0, len = reader.getNamespaceCount(); i < len; i++) {
                element.addNamespaceDeclaration(Namespace.getNamespace(reader.getAttributePrefix(i), reader.getNamespaceURI(i)));
            }
        }

        return element;
    }
}
