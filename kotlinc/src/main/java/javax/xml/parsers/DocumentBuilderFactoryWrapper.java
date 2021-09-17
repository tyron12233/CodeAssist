package javax.xml.parsers;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;

public class DocumentBuilderFactoryWrapper extends DocumentBuilderFactory {

    private static class DocumentBuilderWrapper extends DocumentBuilder {

        private final org.openjdk.javax.xml.parsers.DocumentBuilder mBuilder;

        public DocumentBuilderWrapper(org.openjdk.javax.xml.parsers.DocumentBuilder builder) {
            mBuilder = builder;
        }

        @Override
        public Document parse(InputSource is) throws SAXException, IOException {
            return mBuilder.parse(is);
        }

        @Override
        public boolean isNamespaceAware() {
            return mBuilder.isNamespaceAware();
        }

        @Override
        public boolean isValidating() {
            return mBuilder.isValidating();
        }

        @Override
        public void setEntityResolver(EntityResolver er) {
            mBuilder.setEntityResolver(er);
        }

        @Override
        public void setErrorHandler(ErrorHandler eh) {
            mBuilder.setErrorHandler(eh);
        }

        @Override
        public Document newDocument() {
            return mBuilder.newDocument();
        }

        @Override
        public DOMImplementation getDOMImplementation() {
            return mBuilder.getDOMImplementation();
        }
    }
    private final org.openjdk.javax.xml.parsers.DocumentBuilderFactory mBase;

    public DocumentBuilderFactoryWrapper(org.openjdk.javax.xml.parsers.DocumentBuilderFactory factory) {
        mBase = factory;
    }

    @Override
    public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
        try {
            return new DocumentBuilderWrapper(mBase.newDocumentBuilder());
        } catch (org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new ParserConfigurationException(e.getMessage());
        }
    }

    @Override
    public void setAttribute(String name, Object value) throws IllegalArgumentException {
        mBase.setAttribute(name, value);
    }

    @Override
    public Object getAttribute(String name) throws IllegalArgumentException {
        return mBase.getAttribute(name);
    }

    @Override
    public void setFeature(String name, boolean value) throws ParserConfigurationException {
        try {
            mBase.setFeature(name, value);
        } catch (org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new ParserConfigurationException(e.getMessage());
        }
    }

    @Override
    public boolean getFeature(String name) throws ParserConfigurationException {
        try {
            return mBase.getFeature(name);
        } catch (org.openjdk.javax.xml.parsers.ParserConfigurationException e) {
            throw new ParserConfigurationException(e.getMessage());
        }
    }
}
