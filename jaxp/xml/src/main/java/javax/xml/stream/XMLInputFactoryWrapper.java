package javax.xml.stream;


import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.Iterator;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.stream.util.JDKXMLStreamReader;
import javax.xml.stream.util.JdkXMLEventReader;
import javax.xml.stream.util.XMLEventAllocator;
import javax.xml.stream.util.XMLEventConsumer;
import javax.xml.transform.Source;

public class XMLInputFactoryWrapper extends XMLInputFactory {

    private static class CharactersWrapper extends XMLEventWrapper implements Characters {

        final org.openjdk.javax.xml.stream.events.Characters c;
        public CharactersWrapper(org.openjdk.javax.xml.stream.events.XMLEvent event, org.openjdk.javax.xml.stream.events.Characters c) {
            super(event);
            this.c = c;
        }

        @Override
        public String getData() {
            return c.getData();
        }

        @Override
        public boolean isWhiteSpace() {
            return c.isWhiteSpace();
        }

        @Override
        public boolean isCData() {
            return c.isCData();
        }

        @Override
        public boolean isIgnorableWhiteSpace() {
            return c.isIgnorableWhiteSpace();
        }
    }
    private static class XMLStreamReaderWrapper implements XMLStreamReader {
        
        public org.openjdk.javax.xml.stream.XMLStreamReader mReader;
        
        public XMLStreamReaderWrapper(org.openjdk.javax.xml.stream.XMLStreamReader reader) {
            mReader = reader;
        }

        @Override
        public Object getProperty(String name) throws IllegalArgumentException {
            return mReader.getProperty(name);
        }

        @Override
        public int next() throws XMLStreamException {
            try {
                return mReader.next();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public void require(int type, String namespaceURI, String localName) throws XMLStreamException {
            try {
                mReader.require(type, namespaceURI, localName);
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public String getElementText() throws XMLStreamException {
            try {
                return mReader.getElementText();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public int nextTag() throws XMLStreamException {
            try {
                return mReader.nextTag();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public boolean hasNext() throws XMLStreamException {
            try {
                return mReader.hasNext();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public void close() throws XMLStreamException {
            try {
                mReader.close();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public String getNamespaceURI(String prefix) {
            return mReader.getNamespaceURI();
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
            org.openjdk.javax.xml.namespace.QName attributeName = mReader.getAttributeName(index);
            return new QName(attributeName.getNamespaceURI(), attributeName.getLocalPart(), attributeName.getPrefix());
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
            org.openjdk.javax.xml.namespace.NamespaceContext ctx = mReader.getNamespaceContext();
            return new NamespaceContext() {
                @Override
                public String getNamespaceURI(String s) {
                    return ctx.getNamespaceURI(s);
                }

                @Override
                public String getPrefix(String s) {
                    return ctx.getPrefix(s);
                }

                @Override
                public Iterator getPrefixes(String s) {
                    return ctx.getPrefixes(s);
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
                return mReader.getTextCharacters(sourceStart, target, targetStart, length);
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
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
            org.openjdk.javax.xml.stream.Location location = mReader.getLocation();
            return new Location() {
                @Override
                public int getLineNumber() {
                    return location.getLineNumber();
                }

                @Override
                public int getColumnNumber() {
                    return location.getColumnNumber();
                }

                @Override
                public int getCharacterOffset() {
                    return location.getCharacterOffset();
                }

                @Override
                public String getPublicId() {
                    return location.getPublicId();
                }

                @Override
                public String getSystemId() {
                    return location.getSystemId();
                }
            };
        }

        @Override
        public QName getName() {
            org.openjdk.javax.xml.namespace.QName name = mReader.getName();
            return new QName(name.getNamespaceURI(), name.getLocalPart(), name.getPrefix());
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
            return mReader.isStandalone();
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
    private final org.openjdk.javax.xml.stream.XMLInputFactory mFactory;
    
    public XMLInputFactoryWrapper(org.openjdk.javax.xml.stream.XMLInputFactory factory) {
        mFactory = factory;
    }
    
    @Override
    public XMLStreamReader createXMLStreamReader(Reader reader) throws XMLStreamException {
        try {
            return new XMLStreamReaderWrapper(mFactory.createXMLStreamReader(reader));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLStreamReader createXMLStreamReader(Source source) throws XMLStreamException {
        org.openjdk.javax.xml.transform.Source s = new org.openjdk.javax.xml.transform.Source() {
            @Override
            public void setSystemId(String s) {
                source.getSystemId();
            }

            @Override
            public String getSystemId() {
                return source.getSystemId();
            }
        };
        try {
            return new XMLStreamReaderWrapper(mFactory.createXMLStreamReader(s));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLStreamReader createXMLStreamReader(InputStream stream) throws XMLStreamException {
        try {
            return new XMLStreamReaderWrapper(mFactory.createXMLStreamReader(stream));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLStreamReader createXMLStreamReader(InputStream stream, String encoding) throws XMLStreamException {
        try {
            return new XMLStreamReaderWrapper(mFactory.createXMLStreamReader(stream, encoding));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLStreamReader createXMLStreamReader(String systemId, InputStream stream) throws XMLStreamException {
        try {
            return new XMLStreamReaderWrapper(mFactory.createXMLStreamReader(systemId, stream));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLStreamReader createXMLStreamReader(String systemId, Reader reader) throws XMLStreamException {
        try {
            return new XMLStreamReaderWrapper(mFactory.createXMLStreamReader(systemId, reader));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    private static class StartElementWrapper implements StartElement {

        private final org.openjdk.javax.xml.stream.events.StartElement s;

        public StartElementWrapper(org.openjdk.javax.xml.stream.events.StartElement e) {
            s = e;
        }

        @Override
        public QName getName() {
            return new QName(s.getName().getNamespaceURI(), s.getName().getLocalPart(), s.getName().getPrefix());
        }

        @Override
        public Iterator getAttributes() {
            return s.getAttributes();
        }

        @Override
        public Iterator getNamespaces() {
            return s.getNamespaces();
        }

        @Override
        public Attribute getAttributeByName(QName name) {
            org.openjdk.javax.xml.stream.events.Attribute a = s.getAttributeByName(new org.openjdk.javax.xml.namespace.QName(name.getNamespaceURI(), name.getLocalPart(), name.getPrefix()));
            return new Attribute() {
                @Override
                public QName getName() {
                    return new QName(a.getName().getNamespaceURI(), a.getName().getLocalPart(), a.getName().getPrefix());
                }

                @Override
                public String getValue() {
                    return a.getValue();
                }

                @Override
                public String getDTDType() {
                    return a.getDTDType();
                }

                @Override
                public boolean isSpecified() {
                    return a.isSpecified();
                }

                @Override
                public int getEventType() {
                    return a.getEventType();
                }

                @Override
                public Location getLocation() {
                    return new Location() {
                        @Override
                        public int getLineNumber() {
                            return a.getLocation().getLineNumber();
                        }

                        @Override
                        public int getColumnNumber() {
                            return a.getLocation().getColumnNumber();
                        }

                        @Override
                        public int getCharacterOffset() {
                            return 0;
                        }

                        @Override
                        public String getPublicId() {
                            return a.getLocation().getPublicId();
                        }

                        @Override
                        public String getSystemId() {
                            return a.getLocation().getSystemId();
                        }
                    };
                }

                @Override
                public boolean isStartElement() {
                    return a.isStartElement();
                }

                @Override
                public boolean isAttribute() {
                    return a.isAttribute();
                }

                @Override
                public boolean isNamespace() {
                    return a.isNamespace();
                }

                @Override
                public boolean isEndElement() {
                    return a.isEndElement();
                }

                @Override
                public boolean isEntityReference() {
                    return a.isEntityReference();
                }

                @Override
                public boolean isProcessingInstruction() {
                    return a.isProcessingInstruction();
                }

                @Override
                public boolean isCharacters() {
                    return a.isCharacters();
                }

                @Override
                public boolean isStartDocument() {
                    return a.isStartDocument();
                }

                @Override
                public boolean isEndDocument() {
                    return a.isEndDocument();
                }

                @Override
                public StartElement asStartElement() {
                    return new StartElementWrapper(s.asStartElement());
                }

                @Override
                public EndElement asEndElement() {
                    return new EndElementWrapper(s.asEndElement(), s.asEndElement());
                }

                @Override
                public Characters asCharacters() {
                    return new CharactersWrapper(s.asCharacters(), s.asCharacters());
                }

                @Override
                public QName getSchemaType() {
                    return new QName(s.getSchemaType().getNamespaceURI(), s.getSchemaType().getLocalPart(), s.getSchemaType().getPrefix());
                }

                @Override
                public void writeAsEncodedUnicode(Writer writer) throws XMLStreamException {

                }
            };
        }

        @Override
        public NamespaceContext getNamespaceContext() {
            org.openjdk.javax.xml.namespace.NamespaceContext a = this.s.getNamespaceContext();
            return new NamespaceContext() {
                @Override
                public String getNamespaceURI(String s) {
                    return a.getNamespaceURI(s);
                }

                @Override
                public String getPrefix(String s) {
                    return a.getPrefix(s);
                }

                @Override
                public Iterator getPrefixes(String s) {
                    return a.getPrefixes(s);
                }
            };
        }

        @Override
        public String getNamespaceURI(String prefix) {
            return s.getNamespaceURI(prefix);
        }

        @Override
        public int getEventType() {
            return s.getEventType();
        }

        @Override
        public Location getLocation() {
            org.openjdk.javax.xml.stream.Location l = s.getLocation();
            return new Location() {
                @Override
                public int getLineNumber() {
                    return l.getLineNumber();
                }

                @Override
                public int getColumnNumber() {
                    return l.getColumnNumber();
                }

                @Override
                public int getCharacterOffset() {
                    return l.getCharacterOffset();
                }

                @Override
                public String getPublicId() {
                    return l.getPublicId();
                }

                @Override
                public String getSystemId() {
                    return l.getSystemId();
                }
            };
        }

        @Override
        public boolean isStartElement() {
            return s.isStartElement();
        }

        @Override
        public boolean isAttribute() {
            return s.isAttribute();
        }

        @Override
        public boolean isNamespace() {
            return s.isNamespace();
        }

        @Override
        public boolean isEndElement() {
            return s.isEndElement();
        }

        @Override
        public boolean isEntityReference() {
            return s.isEntityReference();
        }

        @Override
        public boolean isProcessingInstruction() {
            return s.isProcessingInstruction();
        }

        @Override
        public boolean isCharacters() {
            return s.isCharacters();
        }

        @Override
        public boolean isStartDocument() {
            return s.isStartDocument();
        }

        @Override
        public boolean isEndDocument() {
            return s.isEndDocument();
        }

        @Override
        public StartElement asStartElement() {
            return new StartElementWrapper(s.asStartElement());
        }

        @Override
        public EndElement asEndElement() {
            return new EndElementWrapper(s.asEndElement(), s.asEndElement());
        }

        @Override
        public Characters asCharacters() {
            return new CharactersWrapper(s.asCharacters(), s.asCharacters());
        }

        @Override
        public QName getSchemaType() {
            return new QName(s.getSchemaType().getNamespaceURI(), s.getSchemaType().getLocalPart(), s.getSchemaType().getPrefix());
        }

        @Override
        public void writeAsEncodedUnicode(Writer writer) throws XMLStreamException {
            try {
                s.writeAsEncodedUnicode(writer);
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }
    }
    private static class XMLEventWrapper implements XMLEvent {
        
        private final org.openjdk.javax.xml.stream.events.XMLEvent event;
        
        public XMLEventWrapper(org.openjdk.javax.xml.stream.events.XMLEvent event) {
            this.event = event;
        }

        @Override
        public int getEventType() {
            return event.getEventType();
        }

        @Override
        public Location getLocation() {
            return new Location() {
                @Override
                public int getLineNumber() {
                    return event.getLocation().getLineNumber();
                }

                @Override
                public int getColumnNumber() {
                    return event.getLocation().getColumnNumber();
                }

                @Override
                public int getCharacterOffset() {
                    return event.getLocation().getCharacterOffset();
                }

                @Override
                public String getPublicId() {
                    return event.getLocation().getPublicId();
                }

                @Override
                public String getSystemId() {
                    return event.getLocation().getSystemId();
                }
            };
        }

        @Override
        public boolean isStartElement() {
            return event.isStartElement();
        }

        @Override
        public boolean isAttribute() {
            return event.isAttribute();
        }

        @Override
        public boolean isNamespace() {
            return event.isNamespace();
        }

        @Override
        public boolean isEndElement() {
            return event.isEndElement();
        }

        @Override
        public boolean isEntityReference() {
            return event.isEntityReference();
        }

        @Override
        public boolean isProcessingInstruction() {
            return event.isProcessingInstruction();
        }

        @Override
        public boolean isCharacters() {
            return event.isCharacters();
        }

        @Override
        public boolean isStartDocument() {
            return event.isStartDocument();
        }

        @Override
        public boolean isEndDocument() {
            return event.isEndDocument();
        }

        @Override
        public StartElement asStartElement() {
            org.openjdk.javax.xml.stream.events.StartElement s = event.asStartElement();
            return new StartElementWrapper(s);
        }

        @Override
        public EndElement asEndElement() {
            return null;
        }

        @Override
        public Characters asCharacters() {
            return null;
        }

        @Override
        public QName getSchemaType() {
            return null;
        }

        @Override
        public void writeAsEncodedUnicode(Writer writer) throws XMLStreamException {

        }
    }
    private static class XMLEventReaderWrapper implements XMLEventReader {

        private final org.openjdk.javax.xml.stream.XMLEventReader mReader;
        
        public XMLEventReaderWrapper(org.openjdk.javax.xml.stream.XMLEventReader reader) {
            mReader = reader;
        }
        
        @Override
        public XMLEvent nextEvent() throws XMLStreamException {
            try {
                org.openjdk.javax.xml.stream.events.XMLEvent xmlEvent = mReader.nextEvent();
                return new XMLEventWrapper(xmlEvent);
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public boolean hasNext() {
            return mReader.hasNext();
        }

        @Override
        public Object next() {
            return mReader.next();
        }

        @Override
        public XMLEvent peek() throws XMLStreamException {
            try {
                return new XMLEventWrapper(mReader.peek());
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public String getElementText() throws XMLStreamException {
            try {
                return mReader.getElementText();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public XMLEvent nextTag() throws XMLStreamException {
            try {
                return new XMLEventWrapper(mReader.nextTag());
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public Object getProperty(String name) throws IllegalArgumentException {
            return mReader.getProperty(name);
        }

        @Override
        public void close() throws XMLStreamException {
            try {
                mReader.close();
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }
    }

    @Override
    public XMLEventReader createXMLEventReader(Reader reader) throws XMLStreamException {
        try {
            return new XMLEventReaderWrapper(mFactory.createXMLEventReader(reader));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLEventReader createXMLEventReader(String systemId, Reader reader) throws XMLStreamException {
        try {
            return new XMLEventReaderWrapper(mFactory.createXMLEventReader(systemId, reader));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLEventReader createXMLEventReader(XMLStreamReader reader) throws XMLStreamException {
        try {
            return new XMLEventReaderWrapper(mFactory.createXMLEventReader(new JDKXMLStreamReader(reader)));
        } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
            throw new XMLStreamException(e);
        }
    }

    @Override
    public XMLEventReader createXMLEventReader(Source source) throws XMLStreamException {
        return null;
    }

    @Override
    public XMLEventReader createXMLEventReader(InputStream stream) throws XMLStreamException {
        return null;
    }

    @Override
    public XMLEventReader createXMLEventReader(InputStream stream, String encoding) throws XMLStreamException {
        return null;
    }

    @Override
    public XMLEventReader createXMLEventReader(String systemId, InputStream stream) throws XMLStreamException {
        return null;
    }

    @Override
    public XMLStreamReader createFilteredReader(XMLStreamReader reader, StreamFilter filter) throws XMLStreamException {
        return null;
    }

    @Override
    public XMLEventReader createFilteredReader(XMLEventReader reader, EventFilter filter) throws XMLStreamException {
        return null;
    }

    @Override
    public XMLResolver getXMLResolver() {
        return null;
    }

    @Override
    public void setXMLResolver(XMLResolver resolver) {

    }

    @Override
    public XMLReporter getXMLReporter() {
        return null;
    }

    @Override
    public void setXMLReporter(XMLReporter reporter) {

    }

    @Override
    public void setProperty(String name, Object value) throws IllegalArgumentException {

    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        return null;
    }

    @Override
    public boolean isPropertySupported(String name) {
        return false;
    }

    @Override
    public void setEventAllocator(XMLEventAllocator allocator) {

    }


    private static class XMLEventAllocatorWrapper implements XMLEventAllocator {

        private final org.openjdk.javax.xml.stream.util.XMLEventAllocator a;

        public XMLEventAllocatorWrapper(org.openjdk.javax.xml.stream.util.XMLEventAllocator a) {
            this.a = a;
        }
        @Override
        public XMLEventAllocator newInstance() {
            return new XMLEventAllocatorWrapper(a.newInstance());
        }

        @Override
        public XMLEvent allocate(XMLStreamReader reader) throws XMLStreamException {
            try {
                return new XMLEventWrapper(a.allocate(new JDKXMLStreamReader(reader)));
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }

        @Override
        public void allocate(XMLStreamReader reader, XMLEventConsumer consumer) throws XMLStreamException {
            try {
                a.allocate(new JDKXMLStreamReader(reader), new org.openjdk.javax.xml.stream.util.XMLEventConsumer() {
                    @Override
                    public void add(org.openjdk.javax.xml.stream.events.XMLEvent event) throws org.openjdk.javax.xml.stream.XMLStreamException {
                        try {
                            consumer.add(new XMLEventWrapper(event));
                        } catch (XMLStreamException e) {
                            throw new org.openjdk.javax.xml.stream.XMLStreamException(e);
                        }
                    }
                });
            } catch (org.openjdk.javax.xml.stream.XMLStreamException e) {
                throw new XMLStreamException(e);
            }
        }
    }
    @Override
    public XMLEventAllocator getEventAllocator() {
        org.openjdk.javax.xml.stream.util.XMLEventAllocator a = mFactory.getEventAllocator();
        return new XMLEventAllocatorWrapper(a);
    }

    private static class EndElementWrapper extends XMLEventWrapper implements EndElement {

        final org.openjdk.javax.xml.stream.events.EndElement e;
        public EndElementWrapper(org.openjdk.javax.xml.stream.events.XMLEvent event, org.openjdk.javax.xml.stream.events.EndElement e) {
            super(event);
            this.e = e;
        }

        @Override
        public QName getName() {
            return new QName(e.getName().getNamespaceURI(), e.getName().getLocalPart(), e.getName().getPrefix());
        }

        @Override
        public Iterator getNamespaces() {
            return e.getNamespaces();
        }
    }
}
