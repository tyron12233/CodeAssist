package javax.xml.stream.util;


import org.openjdk.javax.xml.stream.XMLEventReader;
import org.openjdk.javax.xml.stream.events.Characters;
import org.openjdk.javax.xml.stream.events.EndElement;

import java.io.Writer;
import java.util.Iterator;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLInputFactoryWrapper;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

public class JdkXMLEventReader implements XMLEventReader {

    private static class EndElementWrapper extends XmlEventWrapper implements EndElement {

        private final javax.xml.stream.events.EndElement a;

        public EndElementWrapper(XMLEvent e, javax.xml.stream.events.EndElement a) {
            super(e);
            this.a = a;
        }

        @Override
        public org.openjdk.javax.xml.namespace.QName getName() {
            return new org.openjdk.javax.xml.namespace.QName(a.getName().getNamespaceURI(), a.getName().getLocalPart(), a.getName().getPrefix());
        }

        @Override
        public Iterator getNamespaces() {
            return a.getNamespaces();
        }
    }
    private static class StartElementWrapper implements org.openjdk.javax.xml.stream.events.StartElement {

        private final StartElement s;

        public StartElementWrapper(StartElement e) {
            s = e;
        }

        @Override
        public org.openjdk.javax.xml.namespace.QName getName() {
            return new org.openjdk.javax.xml.namespace.QName(s.getName().getNamespaceURI(), s.getName().getLocalPart(), s.getName().getPrefix());
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
        public org.openjdk.javax.xml.stream.events.Attribute getAttributeByName(org.openjdk.javax.xml.namespace.QName name) {
            Attribute a = s.getAttributeByName(new QName(name.getNamespaceURI(), name.getLocalPart(), name.getPrefix()));
            return new org.openjdk.javax.xml.stream.events.Attribute() {
                @Override
                public org.openjdk.javax.xml.namespace.QName getName() {
                    return new org.openjdk.javax.xml.namespace.QName(a.getName().getNamespaceURI(), a.getName().getLocalPart(), a.getName().getPrefix());
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
                public org.openjdk.javax.xml.stream.Location getLocation() {
                    return new org.openjdk.javax.xml.stream.Location() {
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
                public org.openjdk.javax.xml.stream.events.StartElement asStartElement() {
                    return new StartElementWrapper(s.asStartElement());
                }

                @Override
                public org.openjdk.javax.xml.stream.events.EndElement asEndElement() {
                    return new EndElementWrapper(s, s.asEndElement());
                }

                @Override
                public org.openjdk.javax.xml.stream.events.Characters asCharacters() {
                    return new CharactersWrapper(s, s.asCharacters());
                }

                @Override
                public org.openjdk.javax.xml.namespace.QName getSchemaType() {
                    return null;
                }

                @Override
                public void writeAsEncodedUnicode(Writer writer) throws org.openjdk.javax.xml.stream.XMLStreamException {

                }
            };
        }

        @Override
        public org.openjdk.javax.xml.namespace.NamespaceContext getNamespaceContext() {
            NamespaceContext a = this.s.getNamespaceContext();
            return new org.openjdk.javax.xml.namespace.NamespaceContext() {
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
        public org.openjdk.javax.xml.stream.Location getLocation() {
            Location l = s.getLocation();
            return new org.openjdk.javax.xml.stream.Location() {
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
        public org.openjdk.javax.xml.stream.events.StartElement asStartElement() {
            return new StartElementWrapper(s.asStartElement());
        }

        @Override
        public org.openjdk.javax.xml.stream.events.EndElement asEndElement() {
            return new EndElementWrapper(s, s.asEndElement());
        }

        @Override
        public org.openjdk.javax.xml.stream.events.Characters asCharacters() {
           return new CharactersWrapper(s, s.asCharacters());
        }

        @Override
        public org.openjdk.javax.xml.namespace.QName getSchemaType() {
            return new org.openjdk.javax.xml.namespace.QName(s.getName().getNamespaceURI(), s.getName().getLocalPart(), s.getName().getPrefix());
        }

        @Override
        public void writeAsEncodedUnicode(Writer writer) throws org.openjdk.javax.xml.stream.XMLStreamException {
            try {
                s.writeAsEncodedUnicode(writer);
            } catch (XMLStreamException e) {
                throw new org.openjdk.javax.xml.stream.XMLStreamException(e);
            }
        }
    }

    private static class CharactersWrapper extends XmlEventWrapper implements org.openjdk.javax.xml.stream.events.Characters {

        private javax.xml.stream.events.Characters c;
        public CharactersWrapper(javax.xml.stream.events.XMLEvent e, javax.xml.stream.events.Characters c) {
            super(e);
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

    private static class XmlEventWrapper implements org.openjdk.javax.xml.stream.events.XMLEvent {

        private final XMLEvent event;
        public XmlEventWrapper(XMLEvent e) {
            event = e;
        }

        @Override
        public int getEventType() {
            return event.getEventType();
        }

        @Override
        public org.openjdk.javax.xml.stream.Location getLocation() {
            return new org.openjdk.javax.xml.stream.Location() {
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
        public org.openjdk.javax.xml.stream.events.StartElement asStartElement() {
            return new StartElementWrapper(event.asStartElement());
        }

        @Override
        public org.openjdk.javax.xml.stream.events.EndElement asEndElement() {
            return new EndElementWrapper(event, event.asEndElement());
        }

        @Override
        public Characters asCharacters() {
            return new CharactersWrapper(event, event.asCharacters());
        }

        @Override
        public org.openjdk.javax.xml.namespace.QName getSchemaType() {
            QName a = event.getSchemaType();
            return new org.openjdk.javax.xml.namespace.QName(a.getNamespaceURI(), a.getLocalPart(), a.getPrefix());
        }

        @Override
        public void writeAsEncodedUnicode(Writer writer) throws org.openjdk.javax.xml.stream.XMLStreamException {
            try {
                event.writeAsEncodedUnicode(writer);
            } catch (XMLStreamException e) {
                throw new org.openjdk.javax.xml.stream.XMLStreamException(e);
            }
        }
    }
    private final XMLStreamReader reader;

    public JdkXMLEventReader(XMLStreamReader reader) {
        this.reader = reader;
    }

    @Override
    public org.openjdk.javax.xml.stream.events.XMLEvent nextEvent() throws org.openjdk.javax.xml.stream.XMLStreamException {
       return null;
    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @Override
    public Object next() {
        return null;
    }

    @Override
    public org.openjdk.javax.xml.stream.events.XMLEvent peek() throws org.openjdk.javax.xml.stream.XMLStreamException {
//        try {
//            return new XmlEventWrapper(reader.peek());
//        } catch (XMLStreamException e) {
//            throw new org.openjdk.javax.xml.stream.XMLStreamException(e);
//        }
        return null;
    }

    @Override
    public String getElementText() throws org.openjdk.javax.xml.stream.XMLStreamException {
        try {
            return reader.getElementText();
        } catch (XMLStreamException e) {
            throw new org.openjdk.javax.xml.stream.XMLStreamException(e);
        }
    }

    @Override
    public org.openjdk.javax.xml.stream.events.XMLEvent nextTag() throws org.openjdk.javax.xml.stream.XMLStreamException {
        return null;
    }

    @Override
    public Object getProperty(String name) throws IllegalArgumentException {
        return reader.getProperty(name);
    }

    @Override
    public void close() throws org.openjdk.javax.xml.stream.XMLStreamException {
        try {
            reader.close();
        } catch (XMLStreamException e) {
            throw new org.openjdk.javax.xml.stream.XMLStreamException(e);
        }
    }
}
