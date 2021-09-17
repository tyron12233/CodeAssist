package javax.xml.stream.util;


import org.openjdk.javax.xml.namespace.NamespaceContext;
import org.openjdk.javax.xml.namespace.QName;
import org.openjdk.javax.xml.stream.Location;
import org.openjdk.javax.xml.stream.XMLStreamException;
import org.openjdk.javax.xml.stream.XMLStreamReader;

import java.util.Iterator;

public class JDKXMLStreamReader implements XMLStreamReader {

    private final javax.xml.stream.XMLStreamReader mReader;

    public JDKXMLStreamReader(javax.xml.stream.XMLStreamReader mReader) {
        this.mReader = mReader;
    }


    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        return mReader.getProperty(name);
    }

    @Override
    public int next() throws XMLStreamException {
        try {
            return mReader.next();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public void require(int type, String namespaceURI, String localName) throws XMLStreamException {
        try {
            mReader.require(type,namespaceURI, localName);
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public String getElementText() throws XMLStreamException {
        try {
            return mReader.getElementText();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw  new XMLStreamException(e);
        }
    }

    @Override
    public int nextTag() throws XMLStreamException {
        try {
            return mReader.nextTag();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public boolean hasNext() throws XMLStreamException {
        try {
            return mReader.hasNext();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public void close() throws XMLStreamException {
        try {
            mReader.close();
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public String getNamespaceURI(String prefix) {
        return mReader.getNamespaceURI(prefix);
    }

    @Override
    public boolean isStartElement() {
        return mReader.isStartElement();
    }

    @Override
    public boolean isEndElement() {
        return mReader.isEndElement();
    }

    @Override
    public boolean isCharacters() {
        return mReader.isCharacters();
    }

    @Override
    public boolean isWhiteSpace() {
        return mReader.isWhiteSpace();
    }

    @Override
    public String getAttributeValue(String namespaceURI, String localName) {
        return mReader.getAttributeValue(namespaceURI, localName);
    }

    @Override
    public int getAttributeCount() {
        return mReader.getAttributeCount();
    }

    @Override
    public QName getAttributeName(int index) {
        javax.xml.namespace.QName a = mReader.getAttributeName(index);
        return new QName(a.getNamespaceURI(), a.getLocalPart(), a.getPrefix());
    }

    @Override
    public String getAttributeNamespace(int index) {
        return mReader.getAttributeNamespace(index);
    }

    @Override
    public String getAttributeLocalName(int index) {
        return mReader.getAttributeLocalName(index);
    }

    @Override
    public String getAttributePrefix(int index) {
        return mReader.getAttributePrefix(index);
    }

    @Override
    public String getAttributeType(int index) {
        return mReader.getAttributeType(index);
    }

    @Override
    public String getAttributeValue(int index) {
        return mReader.getAttributeValue(index);
    }

    @Override
    public boolean isAttributeSpecified(int index) {
        return mReader.isAttributeSpecified(index);
    }

    @Override
    public int getNamespaceCount() {
        return mReader.getNamespaceCount();
    }

    @Override
    public String getNamespacePrefix(int index) {
        return mReader.getNamespacePrefix(index);
    }

    @Override
    public String getNamespaceURI(int index) {
        return mReader.getNamespaceURI(index);
    }

    @Override
    public NamespaceContext getNamespaceContext() {
        return new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return mReader.getNamespaceContext().getNamespaceURI(prefix);
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return mReader.getNamespaceContext().getPrefix(namespaceURI);
            }

            @Override
            public Iterator getPrefixes(String namespaceURI) {
                return mReader.getNamespaceContext().getPrefixes(namespaceURI);
            }
        };
    }

    @Override
    public int getEventType() {
        return mReader.getEventType();
    }

    @Override
    public String getText() {
        return mReader.getText();
    }

    @Override
    public char[] getTextCharacters() {
        return mReader.getTextCharacters();
    }

    @Override
    public int getTextCharacters(int sourceStart, char[] target, int targetStart, int length) throws XMLStreamException {
        try {
            return mReader.getTextCharacters(sourceStart, target,targetStart,length);
        } catch (javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public int getTextStart() {
        return mReader.getTextStart();
    }

    @Override
    public int getTextLength() {
        return mReader.getTextLength();
    }

    @Override
    public String getEncoding() {
        return mReader.getEncoding();
    }

    @Override
    public boolean hasText() {
        return mReader.hasText();
    }

    @Override
    public Location getLocation() {
        return new Location() {
            @Override
            public int getLineNumber() {
                return mReader.getLocation().getLineNumber();
            }

            @Override
            public int getColumnNumber() {
                return mReader.getLocation().getColumnNumber();
            }

            @Override
            public int getCharacterOffset() {
                return mReader.getLocation().getCharacterOffset();
            }

            @Override
            public String getPublicId() {
                return mReader.getLocation().getPublicId();
            }

            @Override
            public String getSystemId() {
                return mReader.getLocation().getSystemId();
            }
        };
    }

    @Override
    public QName getName() {
        return new QName(mReader.getName().getNamespaceURI(), mReader.getName().getLocalPart());
    }

    @Override
    public String getLocalName() {
        return mReader.getLocalName();
    }

    @Override
    public boolean hasName() {
        return mReader.hasName();
    }

    @Override
    public String getNamespaceURI() {
        return mReader.getNamespaceURI();
    }

    @Override
    public String getPrefix() {
        return mReader.getPrefix();
    }

    @Override
    public String getVersion() {
        return mReader.getVersion();
    }

    @Override
    public boolean isStandalone() {
        return false;
    }

    @Override
    public boolean standaloneSet() {
        return mReader.standaloneSet();
    }

    @Override
    public String getCharacterEncodingScheme() {
        return mReader.getCharacterEncodingScheme();
    }

    @Override
    public String getPITarget() {
        return mReader.getPITarget();
    }

    @Override
    public String getPIData() {
        return mReader.getPIData();
    }
}
