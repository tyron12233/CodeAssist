package com.tyron.builder.common.utils;

import com.android.utils.XmlUtils;

import org.openjdk.javax.xml.parsers.ParserConfigurationException;
import org.openjdk.javax.xml.parsers.SAXParser;
import org.openjdk.javax.xml.parsers.SAXParserFactory;
import org.xml.sax.SAXException;

public class XmlUtilsWorkaround {

    public static SAXParserFactory configureSaxFactory(
            SAXParserFactory factory,
            boolean namespaceAware,
            boolean checkDtd
    ) {
        return XmlUtils.configureSaxFactory(factory, namespaceAware, checkDtd);
    }

    public static SAXParser createSaxParser(SAXParserFactory factory) throws ParserConfigurationException, SAXException {
        return XmlUtils.createSaxParser(factory);
    }
}
