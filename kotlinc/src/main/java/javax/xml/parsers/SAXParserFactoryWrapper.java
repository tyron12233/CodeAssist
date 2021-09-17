package javax.xml.parsers;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Parser;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.XMLReader;

public class SAXParserFactoryWrapper extends SAXParserFactory {

    private static class SAXParserWrapper extends SAXParser {

        private final org.openjdk.javax.xml.parsers.SAXParser mParser;

        public SAXParserWrapper(org.openjdk.javax.xml.parsers.SAXParser parser) {
            mParser = parser;
        }
        @Override
        public Parser getParser() throws SAXException {
            return mParser.getParser();
        }

        @Override
        public XMLReader getXMLReader() throws SAXException {
            return mParser.getXMLReader();
        }

        @Override
        public boolean isNamespaceAware() {
            return mParser.isNamespaceAware();
        }

        @Override
        public boolean isValidating() {
            return mParser.isNamespaceAware();
        }

        @Override
        public void setProperty(String name, Object value) throws SAXNotRecognizedException, SAXNotSupportedException {
            mParser.setProperty(name, value);
        }

        @Override
        public Object getProperty(String name) throws SAXNotRecognizedException, SAXNotSupportedException {
            return mParser.getProperty(name);
        }
    }

    private final org.openjdk.javax.xml.parsers.SAXParserFactory mBase;

    public SAXParserFactoryWrapper(org.openjdk.javax.xml.parsers.SAXParserFactory base) {
        mBase = base;
    }

    @Override
    public SAXParser newSAXParser() throws ParserConfigurationException, SAXException {
        try {
            return new SAXParserWrapper(mBase.newSAXParser());
        } catch (org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new ParserConfigurationException(e.getMessage());
        }
    }

    @Override
    public void setFeature(String name, boolean value) throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
        try {
            mBase.setFeature(name, value);
        } catch (org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new ParserConfigurationException(e.getMessage());
        }
    }

    @Override
    public boolean getFeature(String name) throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
        try {
            return mBase.getFeature(name);
        } catch (org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new ParserConfigurationException(e.getMessage());
        }
    }
}
